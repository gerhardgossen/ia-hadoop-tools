/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.archive.hadoop.jobs;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.archive.hadoop.util.FilenameInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.archive.extract.ExtractingResourceFactoryMapper;
import org.archive.extract.ExtractingResourceProducer;
import org.archive.extract.ExtractorOutput;
import org.archive.extract.ProducerUtils;
import org.archive.extract.ResourceFactoryMapper;
import org.archive.extract.WARCMetadataRecordExtractorOutput;
import org.archive.resource.Resource;
import org.archive.resource.ResourceProducer;
import org.archive.util.StringFieldExtractor;
import org.archive.util.StringFieldExtractor.StringTuple;
import org.archive.resource.ResourceProducer;
import org.archive.resource.producer.ARCFile;
import org.archive.resource.producer.EnvelopedResourceFile;
import org.archive.resource.producer.WARCFile;
import java.lang.*;
import org.apache.commons.io.FilenameUtils;
import java.io.PrintWriter;

/**
* WARCMetadataRecordGenerator - Generate WARCMetadataRecord files from (W)ARC files stored in HDFS
*/
public class WARCMetadataRecordGenerator extends Configured implements Tool {
	
	public final static String TOOL_NAME = "WARCMetadataRecordGenerator";
	public final static String TOOL_DESCRIPTION = "Generate WARCMetadataRecord files from (W)ARC files stored in HDFS";
	
	public static final Log LOG = LogFactory.getLog( WARCMetadataRecordGenerator.class );

	public static class WARCMetadataRecordGeneratorMapper extends MapReduceBase implements Mapper<Text, Text, Text, Text> {
		private JobConf jobConf;

		/**
	  	* <p>Configures the job.</p>
	  	* * @param job The job configuration.
		*/

		public void configure( JobConf job ) {
			this.jobConf = job;
		}

		/**
		* Generate WARCMetadataRecord file for the (w)arc file named in the
		* <code>key</code>
		*/
		public void map( Text key, Text value, OutputCollector output, Reporter reporter )throws IOException {
			String path = key.toString();
			LOG.info( "Start: "  + path );
			
			FSDataInputStream fis = null;
			Path inputPath = null;
			String inputBasename = null;
			String outputBasename = null;

			String tempOutputFileString = null;
			String destOutputFileString = null;
			FSDataOutputStream fsdOut = null;
			long millis = System.currentTimeMillis();

			try {
				inputPath = new Path(path);
				fis = FileSystem.get( new java.net.URI( path ), this.jobConf ).open( inputPath );
				inputBasename = inputPath.getName();
			
				if(path.endsWith(".gz")) {
					outputBasename = inputBasename.substring(0,inputBasename.length()-3) + ".metadata";
				} else {
					outputBasename = inputBasename + ".metadata";
				}
			} catch (Exception e) {
                                LOG.error( "Error opening input file to process: " + path, e );
                                throw new IOException( e );
                        }
			
			//open output file handle
			try {
				destOutputFileString = this.jobConf.get("outputDir") + "/" + outputBasename;
				tempOutputFileString =  destOutputFileString + "." + millis + ".TMP";
				fsdOut = FileSystem.get( new java.net.URI( tempOutputFileString ), this.jobConf ).create(new Path (tempOutputFileString), false); 
			} catch (Exception e) {
				LOG.error( "Error opening output file: " + tempOutputFileString, e );
				throw new IOException( e );
			}
			
			ExtractorOutput out;
			ResourceProducer producer;
			ResourceFactoryMapper mapper;
			ExtractingResourceProducer exProducer;

			//data generation phase 
			try {	
				out = new WARCMetadataRecordExtractorOutput(new PrintWriter(fsdOut),this.jobConf.get("outputType"));
				producer = ProducerUtils.getProducer(path.toString());
				mapper = new ExtractingResourceFactoryMapper();
				exProducer = new ExtractingResourceProducer(producer, mapper);
			} catch ( Exception e ) {
				LOG.error( "Error processing file (before record writes): " + path, e );
				throw new IOException( e );
			}
			
			int count = 0;
			Resource r = null;

			while(count < Integer.MAX_VALUE) {
				try {
					r = exProducer.getNext();
					if(r == null) {
						break;
					}
					out.output(r);
				}
				catch ( Exception e ) {
					LOG.error( "Error processing file (record): " + path, e );
					if ( ! this.jobConf.getBoolean( "soft", false ) ) {
						throw new IOException( e );
					}
				}
				count++;
			}

			try {
				fsdOut.close();
				FileSystem.get(new java.net.URI(path), this.jobConf).rename(new Path (tempOutputFileString), new Path (destOutputFileString));
			} catch ( Exception e ) {
				LOG.error( "Error finalizing file (after record writes): " + path, e );
					throw new IOException( e );
			} 
		}
	}

	/**
	* Run the job.
	*/
	public int run( String[] args ) throws Exception {
		if ( args.length < 2 ) {
			printUsage();
			return 1;
		}

		// Create a job configuration
		JobConf job = new JobConf( getConf( ) );

		// Job name uses output dir to help identify it to the operator.
		job.setJobName( "WARCMetadataRecord Generator " + args[0] );

		// The inputs are a list of filenames, use the
		// FilenameInputFormat to pass them to the mappers.
		job.setInputFormat( FilenameInputFormat.class );

		// This is a map-only job, no reducers.
		job.setNumReduceTasks(0);

		// turn off speculative execution
        job.setBoolean("mapred.map.tasks.speculative.execution",false);

		// set timeout to a high value - 20 hours
		job.setInt("mapred.task.timeout",72000000);
	
		job.setOutputFormat(TextOutputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		job.setMapperClass(WARCMetadataRecordGeneratorMapper.class);
		job.setJarByClass(WARCMetadataRecordGenerator.class);

		//extract outlinks by default
		job.set("outputType","outlinks");
		
		int arg = 0;
		while (arg < args.length - 1) {
			if(args[arg].equals("-hopinfo")) {
				job.set("outputType","hopinfo");
				arg++;
			} else if(args[arg].equals("-timeout")) {
				arg++;
				int taskTimeout = Integer.parseInt(args[arg]);
				job.setInt("mapred.task.timeout",taskTimeout);
				arg++;
			} else if(args[arg].equals("-failpct")) {
				arg++;
				int failPct = Integer.parseInt(args[arg]);
				job.setInt("mapred.max.map.failures.percent",failPct);
				arg++;
			} else {
				break;
			}
		}
		
		if(args.length - 2 < arg) {
			printUsage();
			return 1;
		}
	
		String outputDir = args[arg];
		arg++;

		job.set("outputDir",outputDir);
		FileOutputFormat.setOutputPath(job, new Path (outputDir));

		boolean atLeastOneInput = false;
		for (int i = arg;i < args.length; i++) {
			FileSystem inputfs = FileSystem.get( new java.net.URI( args[i] ), getConf() );
			for ( FileStatus status : inputfs.globStatus( new Path( args[i] ) ) ) {
				Path inputPath  = status.getPath();
				atLeastOneInput = true;
				LOG.info( "Add input path: " + inputPath );
				FileInputFormat.addInputPath( job, inputPath );
			}
		}
		if (! atLeastOneInput) {
			LOG.info( "No input files to WARCMetadataRecordGenerator." );
			return 0;
		}

		// Run the job!
		RunningJob rj = JobClient.runJob( job );
		if ( ! rj.isSuccessful( ) ) {
			LOG.error( "FAILED: " + rj.getID() );
			return 2;
		}
		return 0;
	}

	/** 
	* Print usage
	*/
	public void printUsage() {
		String usage = "Usage: WARCMetadataRecordGenerator [OPTIONS] <outputDir> <(w)arcfile>...\n";
		usage+="\tOptions:\n";
		usage+="\t\t-timeout MILLISECONDS - mapred.task.timeout setting (default: 72000000)\n";
		usage+="\t\t-hopinfo - print hopinfo lines instead of outlinks (default)\n";
		usage+="\t\t-failpct PCT - mapred.max.map.failures.percent (default: 0). Set to 10 to allow 10% of map tasks to fail\n";
		System.out.println(usage);
	}

	/**
	* Command-line driver.  Runs the WARCMetadataRecordGenerator as a Hadoop job.
	*/
	public static void main( String args[] ) throws Exception {
		int result = ToolRunner.run(new Configuration(), new WARCMetadataRecordGenerator(), args);
		System.exit( result );
	}

}
