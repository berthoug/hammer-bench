/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.hops.experiments.benchmarks.rawthroughput;

import io.hops.experiments.benchmarks.OperationsUtils;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import io.hops.experiments.benchmarks.common.NamespaceWarmUp;
import io.hops.experiments.controller.Logger;
import io.hops.experiments.controller.commands.WarmUpCommand;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import io.hops.experiments.benchmarks.common.Benchmark;
import io.hops.experiments.controller.commands.BenchmarkCommand;
import io.hops.experiments.utils.BenchmarkUtils;
import io.hops.experiments.benchmarks.common.BenchmarkOperations;
import io.hops.experiments.workload.generator.FilePool;

/**
 *
 * @author salman
 */
public class RawBenchmark extends Benchmark {

  private AtomicInteger successfulOps = new AtomicInteger(0);
  private AtomicInteger failedOps = new AtomicInteger(0);
  private long phaseStartTime;
  private long phaseDurationInMS;
  private final long maxFilesToCreate;
  private String baseDir;
  private short replicationFactor;
  private long fileSize;
  private long appendSize;
  private final int dirsPerDir;
  private final int filesPerDir;
  
  public RawBenchmark(Configuration conf, int numThreads, int dirsPerDir, int filesPerDir, long maxFilesToCreate) {
    super(conf, numThreads);
    this.dirsPerDir = dirsPerDir;
    this.filesPerDir = filesPerDir;
    this.maxFilesToCreate = maxFilesToCreate;
  }

  @Override
  protected WarmUpCommand.Response warmUp(WarmUpCommand.Request warmUpCommand)
          throws IOException, InterruptedException {
    NamespaceWarmUp.Request namespaceWarmUp = (NamespaceWarmUp.Request) warmUpCommand;
    this.replicationFactor = namespaceWarmUp.getReplicationFactor();
    this.fileSize = namespaceWarmUp.getFileSize();
    this.appendSize = namespaceWarmUp.getAppendSize();
    this.baseDir = namespaceWarmUp.getBaseDir();
    List workers = new ArrayList<BaseWarmUp>();
    for (int i = 0; i < numThreads; i++) {
      Callable worker = new BaseWarmUp(namespaceWarmUp.getFilesToCreate(), replicationFactor,
              fileSize, baseDir, dirsPerDir, filesPerDir);
      workers.add(worker);
    }
    executor.invokeAll(workers); // blocking call
    return new NamespaceWarmUp.Response();
  }

  @Override
  protected BenchmarkCommand.Response processCommandInternal(BenchmarkCommand.Request command)
          throws IOException, InterruptedException {
    RawBenchmarkCommand.Request request = (RawBenchmarkCommand.Request) command;
    RawBenchmarkCommand.Response response;
    System.out.println("Starting the " + request.getPhase() + " duration " + request.getDurationInMS());
    response = startTestPhase(request.getPhase(), request.getDurationInMS(), baseDir);
    return response;
  }

  private RawBenchmarkCommand.Response startTestPhase(BenchmarkOperations opType, long duration, String baseDir) throws InterruptedException, UnknownHostException, IOException {
    List workers = new LinkedList<Callable>();
    for (int i = 0; i < numThreads; i++) {
      Callable worker = new Generic(baseDir, opType);
      workers.add(worker);
    }
    setMeasurementVariables(duration);
    executor.invokeAll(workers);// blocking call
    long phaseFinishTime = System.currentTimeMillis();
    long actualExecutionTime = (phaseFinishTime - phaseStartTime);
    
    double speed = ((double) successfulOps.get() / (double) actualExecutionTime); // p / ms
    speed = speed * 1000;

    RawBenchmarkCommand.Response response =
            new RawBenchmarkCommand.Response(opType,
            actualExecutionTime, successfulOps.get(), failedOps.get(), speed);
    return response;
  }

  public class Generic implements Callable {

    private BenchmarkOperations opType;
    private DistributedFileSystem dfs;
    private FilePool filePool;
    private String baseDir;

    public Generic(String baseDir, BenchmarkOperations opType) throws IOException {
      this.baseDir = baseDir;
      this.opType = opType;
    }

    @Override
    public Object call() throws Exception {
      dfs = BenchmarkUtils.getDFSClient(conf);
      filePool = BenchmarkUtils.getFilePool(conf, baseDir, dirsPerDir, filesPerDir);

      while (true) {
        try {

          String path = OperationsUtils.getPath(opType,filePool);

          if (path == null || ((System.currentTimeMillis() - phaseStartTime) > (phaseDurationInMS))) {
            return null;
          }
          
          if(opType == BenchmarkOperations.CREATE_FILE && maxFilesToCreate < (long)(successfulOps.get() + Benchmark.filesCreatedInWarmupPhase.get())){
             if (Logger.canILog()) {
                Logger.printMsg("Finished writing. Created maximum number of files");
             }
            return null;
          } 
          
          OperationsUtils.performOp(dfs,opType,filePool,path,replicationFactor,fileSize, appendSize);
          
          successfulOps.incrementAndGet();

          if (Logger.canILog()) {
            Logger.printMsg("Successful " + opType + " ops " + successfulOps.get() + " Failed ops " + failedOps.get() + " Speed: " + BenchmarkUtils.round(speedPSec(successfulOps, phaseStartTime)));
          }
        } catch (Exception e) {
          failedOps.incrementAndGet();
          Logger.error(e);
        }
      }
    }
  }

  private void setMeasurementVariables(long duration) {
    phaseDurationInMS = duration;
    phaseStartTime = System.currentTimeMillis();
    successfulOps = new AtomicInteger(0);
    failedOps = new AtomicInteger(0);
  }

  public double speedPSec(AtomicInteger ops, long startTime) {
    long timePassed = (System.currentTimeMillis() - startTime);
    double opsPerMSec = (double) (ops.get()) / (double) timePassed;
    return opsPerMSec * 1000;
  }
}
