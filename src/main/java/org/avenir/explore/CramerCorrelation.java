/*
 * avenir: Predictive analytic based on Hadoop Map Reduce
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.avenir.explore;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.avenir.util.ContingencyMatrix;
import org.chombo.mr.FeatureField;
import org.chombo.util.FeatureSchema;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Cramer index for correlation between categorical attributes
 * @author pranab
 *
 */
public class CramerCorrelation extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Categorical data correlation with Cramer index";
        job.setJobName(jobName);
        
        job.setJarByClass(CramerCorrelation.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "avenir");
        job.setMapperClass(CramerCorrelation.CorrelationMapper.class);
        job.setReducerClass(CramerCorrelation.CorrelationReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Text.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        job.setNumReduceTasks(job.getConfiguration().getInt("num.reducer", 1));

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	

	/**
	 * @author pranab
	 *
	 */
	public static class CorrelationMapper extends Mapper<LongWritable, Text, Tuple, Text> {
		private String fieldDelimRegex;
		private String[] items;
		private Text outVal  = new Text();
        private FeatureSchema schema;
 		private int[] sourceAttrs;
		private int[] destAttrs;
		private Map<Tuple, ContingencyMatrix> contMatrices = new HashMap<Tuple, ContingencyMatrix>();
		private List<FeatureField> srcFields = new ArrayList<FeatureField>();
		private List<FeatureField> dstFields = new ArrayList<FeatureField>();
		private List<Tuple> attrPairs = new ArrayList<Tuple>();
        private static final Logger LOG = Logger.getLogger(CorrelationMapper.class);
		
		 
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration conf = context.getConfiguration();
            if (conf.getBoolean("debug.on", false)) {
            	LOG.setLevel(Level.DEBUG);
            }
        	fieldDelimRegex = conf.get("field.delim.regex", ",");
        	InputStream fs = Utility.getFileStream(context.getConfiguration(), "feature.schema.file.path");
            ObjectMapper mapper = new ObjectMapper();
            schema = mapper.readValue(fs, FeatureSchema.class);
        	sourceAttrs = Utility.intArrayFromString(conf.get("source.attributes"), ",");
        	destAttrs = Utility.intArrayFromString(conf.get("dest.attributes"), ",");
        	
        	//initialize contingency matrix for all source attribute and target attribute pair
        	FeatureField srcField = null;
        	FeatureField dstField = null;
        	int srcSize = 0;
        	int dstSize = 0;
    		boolean dstFiledsInitialized = false;
            for (int src : sourceAttrs) {
    			srcField = schema.findFieldByOrdinal(src);
        		srcSize = srcField.getCardinality().size();
        		srcFields.add(srcField);
            	for (int dst : destAttrs) {
            		LOG.debug("attr ordinals:" + src + "  " + dst);
            		if (src != dst) {
            			dstField = schema.findFieldByOrdinal(dst);
	            		dstSize = dstField.getCardinality().size();
	            		LOG.debug("attr cardinality:" + srcSize + "  " + dstSize);
	            		Tuple key = new Tuple();
	            		key.add(src, dst);
	            		ContingencyMatrix value = new ContingencyMatrix(srcSize, dstSize);
	            		contMatrices.put(key, value);
	            		
	            		if (!dstFiledsInitialized) {
	            			dstFields.add(dstField);
	            		}
	            		attrPairs.add(key);
            		}
            	}
        		dstFiledsInitialized = true;
            	
            }
        }
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#cleanup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void cleanup(Context context)  throws IOException, InterruptedException {
        	for (Tuple keyVal : attrPairs) {
        		ContingencyMatrix contMat = contMatrices.get(keyVal);
        		outVal.set(contMat.serialize());
        		context.write(keyVal, outVal);
        	}
        }
        
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
            
            //update contingency matrix for attribute pair
            String srcVal = null;
            String dstVal = null;
            ContingencyMatrix contMat = null;
            int attPairIndex = 0;
            
            for (FeatureField srcField : srcFields) {
        		srcVal = items[srcField.getOrdinal()];
        		int srcIndex = srcField.cardinalityIndex(srcVal);
            	for (FeatureField dstField : dstFields) {
            		dstVal = items[dstField.getOrdinal()];
            		int dstIndex = dstField.cardinalityIndex(dstVal);
            		contMat = contMatrices.get(attrPairs.get(attPairIndex++));
            		contMat.increment(srcIndex, dstIndex);
            	}
            }
        }        
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class CorrelationReducer extends Reducer<Tuple, Text, NullWritable, Text> {
        private FeatureSchema schema;
    	private FeatureField srcField = null;
    	private FeatureField dstField = null;
    	private int srcSize = 0;
    	private int dstSize = 0;
    	private ContingencyMatrix contMat;
		private Text outVal  = new Text();
    	private ContingencyMatrix thisContMat = new ContingencyMatrix();
		private String fieldDelim;
		private int corrScale;
        private static final Logger LOG = Logger.getLogger(CorrelationReducer.class);
		
	   	protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration conf = context.getConfiguration();
            if (conf.getBoolean("debug.on", false)) {
            	LOG.setLevel(Level.DEBUG);
            }
        	InputStream fs = Utility.getFileStream(context.getConfiguration(), "feature.schema.file.path");
            ObjectMapper mapper = new ObjectMapper();
            schema = mapper.readValue(fs, FeatureSchema.class);
        	fieldDelim = conf.get("field.delim.out", ",");
        	corrScale = context.getConfiguration().getInt("correlation.scale", 1000);
	   	}
	   	
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void reduce(Tuple  key, Iterable<Text> values, Context context)
        throws IOException, InterruptedException {
			srcField = schema.findFieldByOrdinal(key.getInt(0));
			dstField = schema.findFieldByOrdinal(key.getInt(1));
    		srcSize = srcField.getCardinality().size();
    		dstSize = dstField.getCardinality().size();
    		contMat = new ContingencyMatrix(srcSize, dstSize);
    		
    		LOG.debug("attr pairs:" + key.getInt(0) + "  " + key.getInt(1));
    		thisContMat.initialize(srcSize, dstSize);
    		for (Text value : values) {
    			LOG.debug("cont matrix:" + value.toString() );
    			thisContMat.deseralize(value.toString());
    			contMat.aggregate(thisContMat);
    		}
        	
    		outVal.set(srcField.getName() + fieldDelim +dstField.getName() + fieldDelim + contMat.cramerIndex());
			context.write(NullWritable.get(),outVal);
        }	   	
	   	
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new CramerCorrelation(), args);
        System.exit(exitCode);
	}
	
}
