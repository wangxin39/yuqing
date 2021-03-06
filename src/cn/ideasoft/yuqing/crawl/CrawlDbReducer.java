/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.ideasoft.yuqing.crawl;

import java.util.ArrayList;
import java.util.Iterator;
import java.io.IOException;

// Commons Logging imports
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import cn.ideasoft.yuqing.metadata.YuQing;
import cn.ideasoft.yuqing.scoring.ScoringFilterException;
import cn.ideasoft.yuqing.scoring.ScoringFilters;

/** Merge new page entries with existing entries. */
public class CrawlDbReducer implements Reducer {
  public static final Log LOG = LogFactory.getLog(CrawlDbReducer.class);
  
  private int retryMax;
  private CrawlDatum result = new CrawlDatum();
  private ArrayList linked = new ArrayList();
  private ScoringFilters scfilters = null;
  private boolean additionsAllowed;

  public void configure(JobConf job) {
    retryMax = job.getInt("db.fetch.retry.max", 3);
    scfilters = new ScoringFilters(job);
    additionsAllowed = job.getBoolean(CrawlDb.CRAWLDB_ADDITIONS_ALLOWED, true);
  }

  public void close() {}

  public void reduce(WritableComparable key, Iterator values,
                     OutputCollector output, Reporter reporter)
    throws IOException {

    CrawlDatum fetch = null;
    CrawlDatum old = null;
    byte[] signature = null;
    linked.clear();

    while (values.hasNext()) {
      CrawlDatum datum = (CrawlDatum)values.next();
      if (CrawlDatum.hasDbStatus(datum)) {
        if (old == null) {
          old = datum;
        } else {
          // always take the latest version
          if (old.getFetchTime() < datum.getFetchTime()) old = datum;
        }
        continue;
      }

      if (CrawlDatum.hasFetchStatus(datum)) {
        if (fetch == null) {
          fetch = datum;
        } else {
          // always take the latest version
          if (fetch.getFetchTime() < datum.getFetchTime()) fetch = datum;
        }
        continue;
      }

      switch (datum.getStatus()) {                // collect other info
      case CrawlDatum.STATUS_LINKED:
        linked.add(datum);
        break;
      case CrawlDatum.STATUS_SIGNATURE:
        signature = datum.getSignature();
        break;
      default:
        LOG.warn("Unknown status, key: " + key + ", datum: " + datum);
      }
    }

    // if it doesn't already exist, skip it
    if (old == null && !additionsAllowed) return;
    
    // if there is no fetched datum, perhaps there is a link
    if (fetch == null && linked.size() > 0) {
      fetch = (CrawlDatum)linked.get(0);
    }
    
    // still no new data - record only unchanged old data, if exists, and return
    if (fetch == null) {
      if (old != null) // at this point at least "old" should be present
        output.collect(key, old);
      else
        LOG.warn("Missing fetch and old value, signature=" + signature);
      return;
    }
    
    // initialize with the latest version, be it fetch or link
    result.set(fetch);
    if (old != null) {
      // copy metadata from old, if exists
      if (old.getMetaData().size() > 0) {
        result.getMetaData().putAll(old.getMetaData());
        // overlay with new, if any
        if (fetch.getMetaData().size() > 0)
          result.getMetaData().putAll(fetch.getMetaData());
      }
      // set the most recent valid value of modifiedTime
      if (old.getModifiedTime() > 0 && fetch.getModifiedTime() == 0) {
        result.setModifiedTime(old.getModifiedTime());
      }
    }
    
    switch (fetch.getStatus()) {                // determine new status

    case CrawlDatum.STATUS_LINKED:                // it was link
      if (old != null) {                          // if old exists
        result.set(old);                          // use it
      } else {
        result.setStatus(CrawlDatum.STATUS_DB_UNFETCHED);
        try {
          scfilters.initialScore((Text)key, result);
        } catch (ScoringFilterException e) {
          if (LOG.isWarnEnabled()) {
            LOG.warn("Cannot filter init score for url " + key +
                     ", using default: " + e.getMessage());
          }
          result.setScore(0.0f);
        }
      }
      break;
      
    case CrawlDatum.STATUS_FETCH_SUCCESS:         // succesful fetch
      if (fetch.getSignature() == null) result.setSignature(signature);
      result.setStatus(CrawlDatum.STATUS_DB_FETCHED);
      result.setNextFetchTime();
      break;

    case CrawlDatum.STATUS_FETCH_REDIR_TEMP:
      if (fetch.getSignature() == null) result.setSignature(signature);
      result.setStatus(CrawlDatum.STATUS_DB_REDIR_TEMP);
      result.setNextFetchTime();
      break;
    case CrawlDatum.STATUS_FETCH_REDIR_PERM:
      if (fetch.getSignature() == null) result.setSignature(signature);
      result.setStatus(CrawlDatum.STATUS_DB_REDIR_PERM);
      result.setNextFetchTime();
      break;
    case CrawlDatum.STATUS_SIGNATURE:
      if (LOG.isWarnEnabled()) {
        LOG.warn("Lone CrawlDatum.STATUS_SIGNATURE: " + key);
      }   
      return;
    case CrawlDatum.STATUS_FETCH_RETRY:           // temporary failure
      if (old != null)
        result.setSignature(old.getSignature());  // use old signature
      if (fetch.getRetriesSinceFetch() < retryMax) {
        result.setStatus(CrawlDatum.STATUS_DB_UNFETCHED);
      } else {
        result.setStatus(CrawlDatum.STATUS_DB_GONE);
      }
      break;

    case CrawlDatum.STATUS_FETCH_GONE:            // permanent failure
      if (old != null)
        result.setSignature(old.getSignature());  // use old signature
      result.setStatus(CrawlDatum.STATUS_DB_GONE);
      break;

    default:
      throw new RuntimeException("Unknown status: " + fetch.getStatus() + " " + key);
    }

    try {
      scfilters.updateDbScore((Text)key, old, result, linked);
    } catch (Exception e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Couldn't update score, key=" + key + ": " + e);
      }
    }
    // remove generation time, if any
    result.getMetaData().remove(YuQing.WRITABLE_GENERATE_TIME_KEY);
    output.collect(key, result);
  }

}
