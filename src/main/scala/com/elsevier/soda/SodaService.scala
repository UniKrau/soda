package com.elsevier.soda

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.collectionAsScalaIterable
import scala.collection.mutable.ArrayBuffer
import org.apache.commons.lang3.StringUtils
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.FacetParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.util.ContentStreamBase
import org.apache.solr.common.util.NamedList
import org.springframework.stereotype.Service
import org.apache.solr.client.solrj.impl.HttpSolrClient.RemoteSolrException
import java.util.Collections
import org.apache.solr.common.util.ContentStream

case class DictInfo(dictName: String, numEntries: Long)

@Service
class SodaService {

    val props = SodaUtils.props()
    val SOLR_URL = "http://localhost:8983/solr/texttagger"

    val solr = new HttpSolrClient(SOLR_URL)
    val phraseChunker = new PhraseChunker()
    val stopwords = SodaUtils.stopwords()

    def annotate(text: String, lexiconName: String, 
            matchFlag: String): List[Annotation] = {
        val lexName = if (lexiconName.endsWith("-full")) 
                          lexiconName.substring(0, lexiconName.length() - 5)
                      else lexiconName
        val annotations = matchFlag match {
            case "exact" => tag(text, lexName, false)
            case "lower" => tag(text, lexName, true)
            case "punct" => chunkAndTag(text, lexName, "tagname_nrm")
            case "sort" => chunkAndTag(text, lexName, "tagname_srt")
            case "stem" => chunkAndTag(text, lexName, "tagname_stm")
            case _ => List()
        }
        if (lexiconName.endsWith("-full")) annotations
        else {
            annotations.filter(annotation => {
                val coveredText = annotation.props(AnnotationHelper.CoveredText)
                val isAbbrev = SodaUtils.isAbbreviation(coveredText)
                val isStopword = SodaUtils.isStopword(coveredText, stopwords)
                val isTooShort = SodaUtils.isTooShort(coveredText)
                isAbbrev || !(isStopword || isTooShort)
            })
        }
    }

    def tag(text: String, lexName: String, 
            lowerCaseInput: Boolean): List[Annotation] = {
        val params = new ModifiableSolrParams()
        params.add("overlaps", "LONGEST_DOMINANT_RIGHT")
        params.add("fq", buildFq(lexName, lowerCaseInput))
        params.add("fl", "id,tagtype,tagname_str")
        val req = new ContentStreamUpdateRequest("")
        val cstream = new ContentStreamBase.StringStream(
            if (lowerCaseInput) text.toLowerCase() else text)
        cstream.setContentType("text/plain")
        req.addContentStream(cstream)
        req.setMethod(SolrRequest.METHOD.POST)
        req.setPath("/tag")
        req.setParams(params)
        val resp = req.process(solr).getResponse()
        // extract the tags
        val tags = resp.get("tags").asInstanceOf[java.util.ArrayList[_]]
            .flatMap(tagInfo => {
                val tagList = tagInfo.asInstanceOf[NamedList[_]]
                val startOffset = tagList.get("startOffset").asInstanceOf[Int]
                val endOffset = tagList.get("endOffset").asInstanceOf[Int]
                val ids = tagList.get("ids").asInstanceOf[java.util.ArrayList[String]]
                ids.map(id => (id, startOffset, endOffset))
            }).toList
        // attach the confidences. If we are doing exact match, then we
        // can be lazy and attach confidence of 1 because these are exact
        // matches, otherwise we need to extract the tagname_str and calculate
        if (lowerCaseInput) {
            val idNameMap = resp.get("response").asInstanceOf[SolrDocumentList]
                .iterator()
                .map(doc => {
                    val id = doc.getFieldValue("id").asInstanceOf[String]
                    val tagType = doc.getFieldValues("tagtype")
                        .map(_.asInstanceOf[String])
                        .filter(_.equals(lexName))
                        .head
                    val names = doc.getFieldValues("tagname_str")
                        .map(_.asInstanceOf[String])
                        .toList
                    (id, (tagType, names))               
                }).toMap
            // remove trailing _ from id for lowercase records
            tags.map(tag => {
                val id = if (tag._1.endsWith("_")) {
                    val idlen = tag._1.length
                    tag._1.substring(0, idlen - 1)
                } else tag._1
                val coveredText = text.substring(tag._2, tag._3)
                val conf = bestScore(coveredText, idNameMap(id)._2) 
                Annotation("lx", id, tag._2, tag._3,
                    Map(AnnotationHelper.CoveredText -> coveredText,
                        AnnotationHelper.Confidence -> AnnotationHelper.confToStr(conf),
                        AnnotationHelper.Lexicon -> idNameMap(tag._1)._1))
            })
        } else {
            val idTagtypeMap = resp.get("response")
                .asInstanceOf[SolrDocumentList]
                .iterator()
                .map(doc => {
                    val id = doc.getFieldValue("id").asInstanceOf[String]
                    val tagtype = doc.getFieldValues("tagtype")
                        .map(_.asInstanceOf[String])
                        .filter(_.equals(lexName))
                        .head
                    (id, tagtype)
                }).toMap
            tags.map(tag => {
                val coveredText = text.substring(tag._2, tag._3)
                Annotation("lx", tag._1, tag._2, tag._3, 
                    Map(AnnotationHelper.CoveredText -> coveredText, 
                        AnnotationHelper.Confidence -> "1.0",
                        AnnotationHelper.Lexicon -> idTagtypeMap(tag._1)))
                })
        }
    }
    
    def chunkAndTag(text: String, lexName: String, 
            matchOnField: String): List[Annotation] = {
        val phrases = phraseChunker.phraseChunk(text, "NP")
        val transformedPhrases = phrases.map(phrase => {
            val suffix = matchOnField.substring(matchOnField.lastIndexOf("_"))
            val transformedPhrase = suffix match {
                case "_nrm" => Normalizer.normalizeCasePunct(phrase._1)
                case "_srt" => Normalizer.sortWords(
                    Normalizer.normalizeCasePunct(phrase._1))
                case "_stm" => Normalizer.stemWords(
                    Normalizer.sortWords(
                    Normalizer.normalizeCasePunct(phrase._1)))
                  case _ => null
            }
            (transformedPhrase, phrase._2, phrase._3)
        })
        // run each of these phrases against
        val tags = ArrayBuffer[Annotation]()
        transformedPhrases.foreach(phrase => {
            val params = new ModifiableSolrParams()
            params.add(CommonParams.Q, matchOnField + ":\"" + phrase._1 + "\"")
            params.add(CommonParams.ROWS, "1")
            params.add(CommonParams.FQ, buildFq(lexName, false))
            params.add(CommonParams.FL, "id,tagname_str")
            val resp = solr.query(params)
            val results = resp.getResults()
            if (results.getNumFound() > 0) {
                val sdoc = results.get(0)
                val id = sdoc.getFieldValue("id").asInstanceOf[String]
                val names = sdoc.getFieldValues("tagname_str")
                    .map(_.asInstanceOf[String])
                    .toList
                val coveredText = text.substring(phrase._2, phrase._3) 
                val score = bestScore(phrase._1, names)
                tags += (Annotation("lx", id, phrase._2, phrase._3, 
                    Map(AnnotationHelper.CoveredText -> coveredText,
                        AnnotationHelper.Confidence -> AnnotationHelper.confToStr(score),
                        AnnotationHelper.Lexicon -> lexName)))
            }
        })
        tags.filter(annot => {
            val coveredText = annot.props(AnnotationHelper.CoveredText)
                .replaceAll("\\p{Punct}", "")
                .toLowerCase()
            coveredText.trim().length() > 3 && !stopwords.contains(coveredText)    
        }).toList
    }
    
    def buildFq(tagtype: String, lowerCaseInput: Boolean): String = {
        val tagSubtypeQuery = Array("tagsubtype", if (lowerCaseInput) "l" else "x")
                                  .mkString(":")
        if (tagtype == null) {
            tagSubtypeQuery
        } else {
            val tagtypeQuery = Array("tagtype", tagtype.toLowerCase())
                                   .mkString(":")
            return Array(tagtypeQuery, tagSubtypeQuery).mkString(" AND ")
        }
    }
    
    def bestScore(matchedSpan: String, names: List[String]): Double = {
        val score = names.map(name =>
                StringUtils.getLevenshteinDistance(matchedSpan, name))
            .sorted
            .head
        if (matchedSpan.length() == 0) 0.0D
        else if (score > matchedSpan.length()) 0.0D
        else (1.0D - (1.0D * score / matchedSpan.length()))                               
    }
    
    def getDictInfo(): List[DictInfo] = {
        val params = new ModifiableSolrParams()
        params.add(CommonParams.Q, "*:*")
        params.add(CommonParams.FQ, "tagsubtype:x")
        params.add(CommonParams.ROWS, "0")
        params.add(FacetParams.FACET, "true")
        params.add(FacetParams.FACET_FIELD, "tagtype")
        val resp = solr.query(params)
        resp.getFacetFields().head
            .getValues()
            .map(v => DictInfo(v.getName(), v.getCount()))
            .toList
    }
    
    def getCoverageInfo(text: String): List[DictInfo] = {
        val params = new ModifiableSolrParams()
        params.add("overlaps", "LONGEST_DOMINANT_RIGHT")
        params.add("fl", "id,tagtype,tagname_str")
        val req = new ContentStreamUpdateRequest("")
        val cstream = new ContentStreamBase.StringStream(text)
        cstream.setContentType("text/plain")
        req.addContentStream(cstream)
        req.setMethod(SolrRequest.METHOD.POST)
        req.setPath("/tag")
        req.setParams(params)
        val resp = req.process(solr).getResponse()
        resp.get("response")
            .asInstanceOf[SolrDocumentList]
            .iterator()
            .flatMap(doc => {
                doc.getFieldValues("tagtype")
                   .map(_.asInstanceOf[String])
                   .map(tt => (tt, 1))
            })
            .toList
            .groupBy(kv => kv._1)
            .map(kv => DictInfo(kv._1, kv._2.size))
            .toList
    }
    
    def getNames(lexName: String, id: String): List[String] = {
        val params = new ModifiableSolrParams()
        params.add("q", "id:%s".format(SodaUtils.escapeLucene(id)))
        params.add("fq", "tagtype:%s".format(lexName))
        params.add("fl", "tagname_str")
        params.add("rows", "1")
        val resp = solr.query(params)
        val results = resp.getResults()
        if (results.getNumFound() == 0) List()
        else {
            results.get(0)
                   .getFieldValues("tagname_str")
                   .map(v => v.asInstanceOf[String])
                   .toList
        }
    }
    
    def getPhraseMatches(lexName: String, phrase: String, matching: String): 
    		List[String] = {
        val fieldName = matching match {
            case "exact" => "tagname_str"
            case "lower" => "tagname_str"
            case "punct" => "tagname_nrm"
            case "sort" => "tagname_srt"
            case "stem" => "tagname_stm"
        }
        val suffix = fieldName.substring(fieldName.lastIndexOf("_"))
        val fieldValue = suffix match {
        	case "_nrm" => Normalizer.normalizeCasePunct(phrase)
            case "_srt" => Normalizer.sortWords(
            		Normalizer.normalizeCasePunct(phrase))
            case "_stm" => Normalizer.stemWords(
                Normalizer.sortWords(
                Normalizer.normalizeCasePunct(phrase)))
            case _ => phrase
        }
        val params = new ModifiableSolrParams()
        params.add("q", "%s:\"%s\"".format(fieldName, 
        	SodaUtils.escapeLucene(fieldValue)))
        params.add("fq", "tagtype:%s".format(lexName))
        params.add("fl", "id,score")
        params.add("rows", "5")
        val resp = solr.query(params)
        val results = resp.getResults()
        if (results.getNumFound() == 0) List()
        else {
            results.iterator()
                .map(doc => {
                    val id = doc.get("id").asInstanceOf[String]
                    if (!id.endsWith("_")) id
                    else id.substring(id.length - 1) 
                }).toList.distinct
        }
    }
}
