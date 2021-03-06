/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.druid.data.input.parquet;

import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.indexer.HadoopDruidIndexerConfig;
import org.apache.druid.indexer.path.StaticPathSpec;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.util.ReflectionUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DruidParquetInputTest
{
  @Test
  public void testReadParquetFile() throws IOException, InterruptedException
  {
    HadoopDruidIndexerConfig config = HadoopDruidIndexerConfig.fromFile(new File(
        "example/wikipedia_hadoop_parquet_job.json")
    );
    Job job = Job.getInstance(new Configuration());
    config.intoConfiguration(job);
    GenericRecord data = getFirstRecord(job, ((StaticPathSpec) config.getPathSpec()).getPaths());

    // field not read, should return null
    assertEquals(data.get("added"), null);
    assertEquals(data.get("page"), new Utf8("Gypsy Danger"));
    assertEquals(
        ((List<InputRow>) config.getParser().parseBatch(data)).get(0).getDimension("page").get(0),
        "Gypsy Danger"
    );
  }

  @Test
  public void testBinaryAsString() throws IOException, InterruptedException
  {
    HadoopDruidIndexerConfig config = HadoopDruidIndexerConfig.fromFile(new File(
        "example/impala_hadoop_parquet_job.json")
    );
    Job job = Job.getInstance(new Configuration());
    config.intoConfiguration(job);
    GenericRecord data = getFirstRecord(job, ((StaticPathSpec) config.getPathSpec()).getPaths());

    InputRow row = ((List<InputRow>) config.getParser().parseBatch(data)).get(0);

    // without binaryAsString: true, the value would something like "[104, 101, 121, 32, 116, 104, 105, 115, 32, 105, 115, 3.... ]"
    assertEquals(row.getDimension("field").get(0), "hey this is &é(-è_çà)=^$ù*! Ω^^");
    assertEquals(row.getTimestampFromEpoch(), 1471800234);
  }

  @Test
  public void testDateHandling() throws IOException, InterruptedException
  {
    List<InputRow> rowsWithString = getAllRows("example/date_test_data_job_string.json");
    List<InputRow> rowsWithDate = getAllRows("example/date_test_data_job_date.json");
    assertEquals(rowsWithDate.size(), rowsWithString.size());

    for (int i = 0; i < rowsWithDate.size(); i++) {
      assertEquals(rowsWithString.get(i).getTimestamp(), rowsWithDate.get(i).getTimestamp());
    }
  }

  private GenericRecord getFirstRecord(Job job, String parquetPath) throws IOException, InterruptedException
  {
    File testFile = new File(parquetPath);
    Path path = new Path(testFile.getAbsoluteFile().toURI());
    FileSplit split = new FileSplit(path, 0, testFile.length(), null);

    DruidParquetInputFormat inputFormat = ReflectionUtils.newInstance(
        DruidParquetInputFormat.class,
        job.getConfiguration()
    );
    TaskAttemptContext context = new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID());

    try (RecordReader reader = inputFormat.createRecordReader(split, context)) {

      reader.initialize(split, context);
      reader.nextKeyValue();
      return (GenericRecord) reader.getCurrentValue();
    }
  }

  private List<InputRow> getAllRows(String configPath) throws IOException, InterruptedException
  {
    HadoopDruidIndexerConfig config = HadoopDruidIndexerConfig.fromFile(new File(configPath));
    Job job = Job.getInstance(new Configuration());
    config.intoConfiguration(job);

    File testFile = new File(((StaticPathSpec) config.getPathSpec()).getPaths());
    Path path = new Path(testFile.getAbsoluteFile().toURI());
    FileSplit split = new FileSplit(path, 0, testFile.length(), null);

    DruidParquetInputFormat inputFormat = ReflectionUtils.newInstance(
        DruidParquetInputFormat.class,
        job.getConfiguration()
    );
    TaskAttemptContext context = new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID());

    try (RecordReader reader = inputFormat.createRecordReader(split, context)) {
      List<InputRow> records = new ArrayList<>();
      InputRowParser parser = config.getParser();

      reader.initialize(split, context);
      while (reader.nextKeyValue()) {
        reader.nextKeyValue();
        GenericRecord data = (GenericRecord) reader.getCurrentValue();
        records.add(((List<InputRow>) parser.parseBatch(data)).get(0));
      }

      return records;
    }
  }
}
