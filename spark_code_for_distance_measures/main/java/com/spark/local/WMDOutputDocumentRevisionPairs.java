package com.spark.local;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.feature.Word2Vec;
import org.apache.spark.mllib.feature.Word2VecModel;

import com.spark.local.StaticClassLibs.*;
import com.spark.wmd.WMD4DocPair;
import com.spark.word2vec.ParagraphVector;

import scala.Tuple2;

public class WMDOutputDocumentRevisionPairs {
	private String inputPath, fileNameFolder, matrixFolder, 
		wmdFolder, word2vecFolder, outputFolder;
	//private static ParagraphVector paragraphVector;
    private boolean hasWord2vec; 
    // dtw tao
    private static double wmd_threshold = 0.5; //Double.POSITIVE_INFINITY
    
    
    public String getInputPath() {
		return inputPath;
	}
	public String getMatrixFolder() {
		return matrixFolder;
	}
	public String getWord2vecFolder() {
		return word2vecFolder;
	}
	public String getFileNameFolder() {
		return fileNameFolder;
	}    
	public String getWmdFolder() {
		return wmdFolder;
	}
	public String getOutputFolder() {
		return outputFolder;
	}
	public boolean getHasWord2vec(){
		return hasWord2vec;
	 }

	public WMDOutputDocumentRevisionPairs(SetParameters setParameters,
									   SparkContext sparkContext, JavaSparkContext sc){ 
		this.inputPath = setParameters.getInputPath();
	    this.fileNameFolder = setParameters.getFileNameFolder();
	    this.matrixFolder = setParameters.getMatrixFolder();
	    this.outputFolder = setParameters.getOutputFolder();
	    this.wmdFolder = setParameters.getWmdFolder();
	    this.word2vecFolder = setParameters.getWord2vecFolder();
	    this.hasWord2vec = new File(this.word2vecFolder).exists()?true:false;    
	}
	
	
	public boolean calSign(List<Tuple2<String, Integer>>threshld_count_list, int index){
		Tuple2<String, Integer> t = threshld_count_list.get(index);
		Tuple2<String, Integer> t_large = threshld_count_list.get(index + 1);
		if (t_large._2() > t._2()) return true;
		return false;
	}
	
	public double findDeri(List<Tuple2<String, Integer>>threshld_count_list){
		int threshld_list_size= threshld_count_list.size();
		if (threshld_count_list.size()> 3){
			int index_small = 0;
			int index_large = threshld_list_size - 1;
			int index_mid = (int) (0.5 *(index_small + index_large));
			while (index_small <= 0.5 * threshld_list_size && 
					index_large >= 0.5 * threshld_list_size){
					
					Tuple2<String, Integer> t_s = threshld_count_list.get(index_small);
					Tuple2<String, Integer> t_l = threshld_count_list.get(index_large);
					Tuple2<String, Integer> t_m = threshld_count_list.get(index_mid);
					
					double tem_threshold = Double.parseDouble(t_m._1());
				
					if ((t_m._2() - t_s._2()) * (t_l._2() - t_m._2()) <= 0){
						wmd_threshold = tem_threshold;
						if (calSign(threshld_count_list, index_mid) == 
								calSign(threshld_count_list, index_small))
							index_small = index_mid;
						else
							index_large = index_mid;
					}else{
						break;
					}				
			}			
		}
		return wmd_threshold;
	} 
	
	public static void main(String[] args) {
		SetParameters setParameters = new SetParameters();	
	    SparkConf conf = new SparkConf()
	    // Note that we run with local[2], 
	    // meaning two threads - which represents
	    // which can help detect bugs that only exist when we run in a distributed context.
	    // in reality, the number of clusters also depends on the data set
	    .setMaster(setParameters.getMaster())
	    .setAppName("Document Revision")
	    .set("spark.driver.allowMultipleContexts", "true"); 				
	    SparkContext sparkContext = new SparkContext(conf);
	    JavaSparkContext sc = new JavaSparkContext(sparkContext);
	    
	    WMDOutputDocumentRevisionPairs outputDocumentRevisionPairs = 
	    		new WMDOutputDocumentRevisionPairs(setParameters, sparkContext, sc);
	    CalVSM calVSM = new CalVSM(setParameters, sc);	 
	    calVSM.cal();
	    
	    // Calculate DTW dist
		Word2VecModel model;
		if (outputDocumentRevisionPairs.getHasWord2vec()){
			System.out.println("Using word2vec model from " +
					outputDocumentRevisionPairs.getWord2vecFolder());
			model = Word2VecModel.load(sparkContext, 
					outputDocumentRevisionPairs.getWord2vecFolder());
		}else{
		    JavaRDD<List<String>> corpusLarge_txtNames_rowRdd = 
		    		sc.textFile(outputDocumentRevisionPairs.getInputPath())
		    		.map(new SplitWords()); 
		    Word2Vec word2vec = new Word2Vec()
		  	      .setVectorSize(10)
		  	      .setSeed(42L);
	  	    model = word2vec.fit(corpusLarge_txtNames_rowRdd);    
		    model.save(sparkContext, 
		    		outputDocumentRevisionPairs.getWord2vecFolder());			
		}
		
		ParagraphVector paragraphVector = new ParagraphVector(sparkContext, model);
		final Broadcast <ParagraphVector> paragraphVector_broadcast = 
				sc.broadcast(paragraphVector);
		
		JavaRDD<String> fileNames_lines = sc
				.textFile(outputDocumentRevisionPairs.getFileNameFolder());
		final Broadcast<List<String>> fileName_list_broadcast = sc
				.broadcast(fileNames_lines.collect());
	    
		JavaRDD<String> matrix_lines = 
				sc.textFile(outputDocumentRevisionPairs.getMatrixFolder());
		JavaPairRDD<String, Double> indexValues = matrix_lines
				.mapToPair(new GetPredictedPairSimilarity());
		
		JavaPairRDD<String, Double> filtered_indexValues = indexValues
				.filter(new FilterSmallSimilarities());

		JavaPairRDD<String, String> doc_parent_rdd = filtered_indexValues
				.mapToPair(new PairFunction <Tuple2<String, Double>, String, String>() {

					@Override
					public Tuple2<String, String> call(
							Tuple2<String, Double> x) throws Exception {
						// broadcast value for fileName_list
						List<String> fileName_list = fileName_list_broadcast.getValue();
						String [] items = x._1().split(",");
						// doc
						String doc = fileName_list.get(Integer.parseInt(items[0]));
						// parent
						String parent = fileName_list.get(Integer.parseInt(items[1]));
						return new Tuple2(doc, parent);
					}
				
		}); 

		//-------------------------------------
		// CONVERT RESULTS TO (FILENAME1, vectorizedContent1, FILENAME2, vectorizedContent2) 
		// txt files in the current corpus
		JavaPairRDD<String, String> raw_fileNameContentsRDD = sc
				.wholeTextFiles(outputDocumentRevisionPairs.getInputPath());
		
		JavaPairRDD<String, String> fileNameContentsRDD = raw_fileNameContentsRDD
				.mapToPair(new PairFunction<Tuple2<String, String>, String, String>() {
					
					@Override
					public Tuple2<String, String> call(
							Tuple2<String, String> x) throws Exception {
						ParagraphVector paragraphVector = paragraphVector_broadcast.getValue();
						// base name
						String txt_name = FilenameUtils.getBaseName(x._1())+".txt";
						// content
						// replace content with a tree with vectors (paragraph --> word vectors --> tree)
						String content = paragraphVector.replaceWordWithVector(x._2());
						return new Tuple2(txt_name, content);
					}
					
			});	
					
		fileNameContentsRDD.cache();
		
		JavaPairRDD<String, Tuple2<String, String>> doc_parent_docContent = doc_parent_rdd
				.join(fileNameContentsRDD);
		
		JavaPairRDD<String, Tuple2<String, String>> parent_doc_docContent_rdd = doc_parent_docContent
				.mapToPair(new SwapKey2Parent());
		
		JavaPairRDD<String, Tuple2<Tuple2<String, String>, String>> parent_docContent_parentContent_rdd = 
				parent_doc_docContent_rdd.join(fileNameContentsRDD);
		
		JavaPairRDD<Tuple2<String, String>, Tuple2<String, String>> docContent_parentContent_rdd = 
				parent_docContent_parentContent_rdd
				.mapToPair(new ConvertKey2DocTuple());	

		
		//------------------------------------- 
			
		// WMD distance for all doc pairs
		JavaPairRDD<String, Double> revised_old_distance_RDD = docContent_parentContent_rdd
				.mapToPair(new GetDocPairSimilarity());
		revised_old_distance_RDD.saveAsTextFile(outputDocumentRevisionPairs.getWmdFolder());	
		// end of cal DTW dist
		
		//JavaRDD<String> dtw_lines = sc.textFile(outputDocumentRevisionPairs.getWmdFolder());
		//JavaPairRDD<String, Double> dtw_indexValues = dtw_lines
		//		.mapToPair(new GetDTWDist());  
		JavaPairRDD<String, Double> dtw_indexValues = revised_old_distance_RDD;
		
    	/*
		 * Estimate the optimal tau
		 */
		/*
		final Accumulator<Double> dist_sum = sc.accumulator(0.0);	
		JavaRDD<Double> filteredNumbers = filtered_indexValues.map(
				new Function<Tuple2<String, Double>, Double>() {
				private static final long serialVersionUID = 1L;
	
				public Double call(Tuple2<String, Double> s) { 
					dist_sum.add(s._2());
					return s._2(); 
				}
		});
		System.err.println("filteredNumbers " + filteredNumbers.take(3));
	    System.err.println("dist sum is " + dist_sum.value());
	    final Broadcast<Double> dist_sum_broadcast = sc.broadcast(dist_sum.value());
	    JavaRDD<String> formattedNumber_string = filteredNumbers.map( 
	    		new Function<Double, String>() {
	
				public String call(Double d) { 
					d = d / (Double) dist_sum_broadcast.value();
					DecimalFormat df = new DecimalFormat("#.##");
					return df.format(d);
				}
		});
	    System.err.println("formattedNumber_string " + formattedNumber_string.take(3));
	    
		// calculate dist distribution
		
		JavaPairRDD<String, Integer> threshold_counts = formattedNumber_string
				.mapToPair(new AddCountOne())
				.reduceByKey(new AddUpOnes())
				.sortByKey(); // ascending 0 --> 1
		System.err.println("threshold_counts " + threshold_counts.take(3));		
		List<Tuple2<String, Integer>>threshld_count_list = threshold_counts.collect();
		
		dtw_threshold = outputDocumentRevisionPairs.findDeri(threshld_count_list);
		*/
		System.err.println("dtw_threshold is " + wmd_threshold);
		final Broadcast<Double> dtw_threshold_broadCast = sc.broadcast(wmd_threshold);		
		// End of estimating the optimal tau
		
		JavaPairRDD<String, Double> dtw_filtered_indexValues;
		if (wmd_threshold < Double.POSITIVE_INFINITY){
			dtw_filtered_indexValues = dtw_indexValues
					.filter(new Function <Tuple2<String, Double>, Boolean> (){
						/**
						 * PairRDD <String, Double> to PairRDD <String, Double>
						 */
						private static final long serialVersionUID = 1L;

						public Boolean call(Tuple2<String, Double> s) { 
							if (s._2() <= dtw_threshold_broadCast.value()) 
								return true; 
							return false; }
			});			
		}else{
			dtw_filtered_indexValues = dtw_indexValues;
		}
		
		JavaPairRDD<String, Tuple2<String, Double>> doc_parentDist_rdd = dtw_filtered_indexValues
				.mapToPair(new GetDocParentCandidateDist());
		System.err.println("doc_parent_rdd " + doc_parentDist_rdd.take(2));
		
		JavaPairRDD<String, Tuple2<String, Double>> result_rdd = doc_parentDist_rdd
				.reduceByKey(new DTWGetDocParent());
		
		String period = "11";
		//String output_folder = "." + File.separator + "output"+period+"_"+ dtw_threshold +"_vsm";
		int my_revision_count = 0;
		int true_positive_count = 0;
		int true_revision_count = 266;//should be the number of revised documents	288  		
		List<Tuple2<String, Tuple2<String, Double>>>  doc_parent_list = result_rdd.collect();
		List<Tuple2<String, String>> result_tuple2_rdd = new ArrayList<Tuple2<String, String>>();
		for (Tuple2<String, Tuple2<String, Double>> doc_parent : doc_parent_list){
			
			String doc = FilenameUtils.getBaseName(doc_parent._1());
			String parent_doc = FilenameUtils.getBaseName(doc_parent._2()._1());
			System.out.println(doc +" -- "+ parent_doc);
			if (doc.startsWith(period.substring(period.length()-1, period.length())+"_") ){//
				if (doc.contains(parent_doc)) {
					true_positive_count++;
					System.out.println(doc +" ---- "+ parent_doc);
				}
			result_tuple2_rdd.add(new Tuple2(doc, parent_doc));
			my_revision_count++;				
			}
		}
		
	    // Evaluation  
		double precision = (double)true_positive_count/my_revision_count;
		double recall = (double)true_positive_count/true_revision_count;
		double f_measure = 2 * precision * recall/ (precision + recall);
		System.err.println("true_positive_count:"+true_positive_count);
		System.err.println("my_revision_count: "+my_revision_count);
		System.err.println("true_revision_count: "+true_revision_count);
		System.out.println("precision-wmd_threshold-"+wmd_threshold+": " +precision);
		System.out.println("recall-wmd_threshold-"+wmd_threshold+": " +recall);	
		System.out.println("f-measure-wmd_threshold-"+wmd_threshold+": " +f_measure);
		
		ArrayList <Double> values = new ArrayList<Double>();
		values.add(precision);
		values.add(recall);
		values.add(f_measure);
		JavaRDD<Double> values_rdd = sc.parallelize(values);
		//JavaRDD<Tuple2<String, String>> correct_result_rdd = sc.parallelize(result_tuple2_rdd);
		values_rdd.saveAsTextFile(outputDocumentRevisionPairs.getOutputFolder());			
		
		//System.err.println("result_rdd" + correct_result_rdd.take(2));
		//correct_result_rdd.saveAsTextFile(outputDocumentRevisionPairs.getOutputFolder());		
	    // Close the REPL terminal
	    System.exit(0);	

	}// End of main()
	
	private static class GetDTWDist implements 
	PairFunction<String, String, Double>{
	/**
	 * 
	*/
	private static final long serialVersionUID = 1L;

		@Override
		public Tuple2<String, Double> call(String x)
				throws Exception {
			String [] items = x.replace("(", "").replace(")", "").split(",");
			if (items.length ==2){
				// key: doc,parent
				// value: similarity score
				return new Tuple2<String, Double>(items[0], Double.parseDouble(items[1]));
			}else return new Tuple2(0, Double.POSITIVE_INFINITY);
		}
	};

	
	private static class GetDocParentCandidateDist implements
	PairFunction<Tuple2<String, Double>, String, Tuple2<String, Double>> {

	/**
	 * 
	*/
	private static final long serialVersionUID = 1L;

		public Tuple2<String, Tuple2<String, Double>> call(
				Tuple2<String, Double> x) throws Exception {
			String [] items = x._1().split(";");
			// doc
			String index = FilenameUtils.getBaseName(items[0]);
			// parent_doc					
			// parent_doc, converted dtw dist
			//String value = FilenameUtils.getBaseName(items[1]) + "," + x._2();
			
			return new Tuple2 <String, Tuple2<String, Double>>(index, 
					new Tuple2<String, Double>(FilenameUtils.getBaseName(items[1]),x._2()));
		}
	
	};
	
	
	public static class SplitWords implements Function<String, List<String>> {
		    public List<String> call(String s) {
			      return Arrays.asList(s.split(" "));
	}};
	
	public static class FilterDTWHighDistance implements Function <Tuple2<String, Double>, Boolean> {
		/**
		 * PairRDD <String, Double> to PairRDD <String, Double>
		 */
		private static final long serialVersionUID = 1L;

		public Boolean call(Tuple2<String, Double> s) { if (s._2() < wmd_threshold) return true; return false; }
	};
	
	
	private static class SwapKey2Parent implements 
		PairFunction<Tuple2<String, Tuple2<String, String>>, 
		String, Tuple2<String, String>> {
			
		/**
		 * 
		*/
		private static final long serialVersionUID = 1L;

			@Override
			public Tuple2<String, Tuple2<String, String>> call(
					Tuple2<String, Tuple2<String, String>> x) throws Exception {
				// parent
				String parent =  x._2()._1();
				// doc content
				String docContent = x._2()._2();							
				return new Tuple2<String, Tuple2<String, String>>
					(parent, new Tuple2<String, String>(x._1(), docContent));
			}
	};

	private static class ConvertKey2DocTuple implements 
		PairFunction<Tuple2<String, Tuple2<Tuple2<String, String>, String>>, 
		Tuple2<String, String>, Tuple2<String, String>> {
	
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

			@Override
			public Tuple2<Tuple2<String, String>, Tuple2<String, String>> call(
					Tuple2<String, Tuple2<Tuple2<String, String>, String>> x) throws Exception {
				// doc
				String doc = x._2()._1()._1();							
				// doc content
				String docContent = x._2()._1()._2();
				
				// parent
				String parent = x._1();
				// parent content
				String parentContent =  x._2()._2();							
				return new Tuple2(new Tuple2(doc, docContent), new Tuple2(parent, parentContent));
			}
	};
	
	
	// wmd distance between two documents
	private static class GetDocPairSimilarity implements 
	PairFunction<Tuple2<Tuple2<String, String>, Tuple2<String, String>>, String, Double>{
		/**
		 * Convert RDD<Row> to PairRDD<String, String>
		 */
		private static final long serialVersionUID = 1L;

		public Tuple2<String, Double> call(
				Tuple2<Tuple2<String, String>, Tuple2<String, String>> x) throws Exception {
			String revised_doc_fileName = x._1._1;
			String old_doc_fileName =  x._2._1;
			// document content
			String revised_doc_vector_content = x._1._2;		
			String old_doc_vector_content = x._2._2;	
			WMD4DocPair wmd4DocPair = new WMD4DocPair();
			Double revisedDoc_oLdDoc_distance;
			try{
				revisedDoc_oLdDoc_distance = wmd4DocPair
					.getWMDDist(revised_doc_vector_content, old_doc_vector_content);
			}catch (Exception e){
				
				revisedDoc_oLdDoc_distance = Double.POSITIVE_INFINITY;
			}


			return new Tuple2<String, Double>(revised_doc_fileName + ";" 
			+ old_doc_fileName, revisedDoc_oLdDoc_distance);
	}};
	
}
