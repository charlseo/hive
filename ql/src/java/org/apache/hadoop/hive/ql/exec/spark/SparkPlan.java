/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.spark;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.parse.ExplainConfiguration;
import org.apache.hadoop.hive.ql.plan.ExplainWork;
import org.apache.hadoop.mapred.JobConf;
import org.apache.spark.SparkContext;
import org.apache.spark.util.CallSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.ql.io.HiveKey;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.io.BytesWritable;
import org.apache.spark.api.java.JavaPairRDD;

import com.google.common.base.Preconditions;

@SuppressWarnings("rawtypes")
public class SparkPlan {
  private static final String CLASS_NAME = SparkPlan.class.getName();
  private static final Logger LOG = LoggerFactory.getLogger(SparkPlan.class);
  private final PerfLogger perfLogger = SessionState.getPerfLogger();

  private final Set<SparkTran> rootTrans = new HashSet<SparkTran>();
  private final Set<SparkTran> leafTrans = new HashSet<SparkTran>();
  private final Map<SparkTran, List<SparkTran>> transGraph = new HashMap<SparkTran, List<SparkTran>>();
  private final Map<SparkTran, List<SparkTran>> invertedTransGraph = new HashMap<SparkTran, List<SparkTran>>();
  private final Set<Integer> cachedRDDIds = new HashSet<Integer>();

  private final JobConf jobConf;
  private final SparkContext sc;

  SparkPlan(JobConf jobConf, SparkContext sc) {
    this.jobConf = jobConf;
    this.sc = sc;
  }

  @SuppressWarnings("unchecked")
  public JavaPairRDD<HiveKey, BytesWritable> generateGraph() {
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.SPARK_BUILD_RDD_GRAPH);
    Map<SparkTran, JavaPairRDD<HiveKey, BytesWritable>> tranToOutputRDDMap
        = new HashMap<SparkTran, JavaPairRDD<HiveKey, BytesWritable>>();
    for (SparkTran tran : getAllTrans()) {
      JavaPairRDD<HiveKey, BytesWritable> rdd = null;
      List<SparkTran> parents = getParents(tran);
      if (parents.size() == 0) {
        // Root tran, it must be MapInput
        Preconditions.checkArgument(tran instanceof MapInput,
            "AssertionError: tran must be an instance of MapInput");
        sc.setCallSite(CallSite.apply(tran.getName(), getLongFormCallSite(tran)));
        rdd = tran.transform(null);
      } else {
        for (SparkTran parent : parents) {
          JavaPairRDD<HiveKey, BytesWritable> prevRDD = tranToOutputRDDMap.get(parent);
          if (rdd == null) {
            rdd = prevRDD;
          } else {
            sc.setCallSite(CallSite.apply("UnionRDD (" + rdd.name() + ", " +
                            prevRDD.name() + ")", ""));
            rdd = rdd.union(prevRDD);
            rdd.setName("UnionRDD (" + rdd.getNumPartitions() + ")");
          }
        }
        sc.setCallSite(CallSite.apply(tran.getName(), getLongFormCallSite(tran)));
        rdd = tran.transform(rdd);
      }

      tranToOutputRDDMap.put(tran, rdd);
    }

    JavaPairRDD<HiveKey, BytesWritable> finalRDD = null;
    for (SparkTran leafTran : leafTrans) {
      JavaPairRDD<HiveKey, BytesWritable> rdd = tranToOutputRDDMap.get(leafTran);
      if (finalRDD == null) {
        finalRDD = rdd;
      } else {
        sc.setCallSite(CallSite.apply("UnionRDD (" + rdd.name() + ", " + finalRDD.name() + ")",
                ""));
        finalRDD = finalRDD.union(rdd);
        finalRDD.setName("UnionRDD (" + finalRDD.getNumPartitions() + ")");
      }
    }

    perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.SPARK_BUILD_RDD_GRAPH);

    LOG.info("\n\nSpark RDD Graph:\n\n" + finalRDD.toDebugString() + "\n");

    return finalRDD;
  }

  /**
   * Takes a {@link SparkTran} object that creates the longForm for the RDD's {@link CallSite}.
   * It does this my creating an {@link ExplainTask} and running it over the
   * {@link SparkTran#getBaseWork()} object. The explain output is serialized to the string,
   * which is logged and returned. If any errors are encountered while creating the explain plan,
   * an error message is simply logged, but no {@link Exception} is thrown.
   *
   * @param tran the {@link SparkTran} to create the long call site for
   *
   * @return a {@link String} containing the explain plan for the given {@link SparkTran}
   */
  private String getLongFormCallSite(SparkTran tran) {
    if (this.jobConf.getBoolean(HiveConf.ConfVars.HIVE_SPARK_LOG_EXPLAIN_WEBUI.varname, HiveConf
            .ConfVars.HIVE_SPARK_LOG_EXPLAIN_WEBUI.defaultBoolVal)) {
      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.SPARK_CREATE_EXPLAIN_PLAN + tran.getName());

      ExplainWork explainWork = new ExplainWork();
      explainWork.setConfig(new ExplainConfiguration());
      ExplainTask explainTask = new ExplainTask();
      explainTask.setWork(explainWork);

      String explainOutput = "";
      try {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        explainTask.outputPlan(tran.getBaseWork(), new PrintStream(outputStream), false, false, 0,
                null, this.jobConf.getBoolean(HiveConf.ConfVars.HIVE_IN_TEST.varname,
                        HiveConf.ConfVars.HIVE_IN_TEST.defaultBoolVal));
        explainOutput = StringUtils.abbreviate(tran.getName() + " Explain Plan:\n\n" + outputStream
                .toString(), 100000);
        LOG.debug(explainOutput);
      } catch (Exception e) {
        LOG.error("Error while generating explain plan for " + tran.getName(), e);
      }

      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.SPARK_CREATE_EXPLAIN_PLAN + tran.getName());
      return explainOutput;
    }
    return "";
  }

  public void addTran(SparkTran tran) {
    rootTrans.add(tran);
    leafTrans.add(tran);
  }

  public void addCachedRDDId(int rddId) {
    cachedRDDIds.add(rddId);
  }

  public Set<Integer> getCachedRDDIds() {
    return cachedRDDIds;
  }

  /**
   * This method returns a topologically sorted list of SparkTran.
   */
  private List<SparkTran> getAllTrans() {
    List<SparkTran> result = new LinkedList<SparkTran>();
    Set<SparkTran> seen = new HashSet<SparkTran>();

    for (SparkTran leaf: leafTrans) {
      // make sure all leaves are visited at least once
      visit(leaf, seen, result);
    }

    return result;
  }

  private void visit(SparkTran child, Set<SparkTran> seen, List<SparkTran> result) {
    if (seen.contains(child)) {
      // don't visit multiple times
      return;
    }

    seen.add(child);

    for (SparkTran parent: getParents(child)) {
      if (!seen.contains(parent)) {
        visit(parent, seen, result);
      }
    }

    result.add(child);
  }

  /**
   * Connects the two SparkTrans in the graph.  Does not allow multiple connections
   * between the same pair of SparkTrans.
   * @param parent
   * @param child
   */
  public void connect(SparkTran parent, SparkTran child) {
    rootTrans.remove(child);
    leafTrans.remove(parent);
    if (transGraph.get(parent) == null) {
      transGraph.put(parent, new LinkedList<SparkTran>());
    }
    if (invertedTransGraph.get(child) == null) {
      invertedTransGraph.put(child, new LinkedList<SparkTran>());
    }
    transGraph.get(parent).add(child);
    invertedTransGraph.get(child).add(parent);
  }

  public List<SparkTran> getParents(SparkTran tran) {
    if (!invertedTransGraph.containsKey(tran)) {
      return new ArrayList<SparkTran>();
    }

    return invertedTransGraph.get(tran);
  }

  public List<SparkTran> getChildren(SparkTran tran) {
    if (!transGraph.containsKey(tran)) {
      return new ArrayList<SparkTran>();
    }

    return transGraph.get(tran);
  }

}
