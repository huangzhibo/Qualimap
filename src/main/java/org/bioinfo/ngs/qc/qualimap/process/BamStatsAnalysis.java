package org.bioinfo.ngs.qc.qualimap.process;

import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import org.bioinfo.commons.log.Logger;
import org.bioinfo.commons.utils.StringUtils;
import org.bioinfo.formats.core.sequence.Fasta;
import org.bioinfo.formats.core.sequence.io.FastaReader;
import org.bioinfo.ngs.qc.qualimap.beans.*;
import org.bioinfo.ngs.qc.qualimap.gui.utils.Constants;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by  kokonech
 * Date: 11/7/11
 * Time: 4:06 PM
 */
public class BamStatsAnalysis {

    // input data
	private String bamFile;
	private String referenceFile;

	// reference
	private boolean referenceAvailable;
	private byte[] reference;
	private long referenceSize;
	private int numberOfReferenceContigs;

	// currentWindow management
	private int numberOfWindows;
	private int effectiveNumberOfWindows;
	private int windowSize;

	// coordinates transformer
	private GenomeLocator locator;

	// globals
	private int numberOfReads;
	private int numberOfValidReads;
	private double percentageOfValidReads;
	private int numberOfMappedReads;
	private double percentageOfMappedReads;
    private int numberOfDuplicatedReads;

	// statistics
	private BamStats bamStats;

	private Logger logger;

	// working variables
	private BamGenomeWindow currentWindow;
	private ConcurrentMap<Long,BamGenomeWindow> openWindows;

	// nucleotide reporting
	private String outdir;

	// gff support
	private boolean selectedRegionsAvailable;
	private String gffFile;
	private int numberOfSelectedRegions;

	// inside
	private long insideReferenceSize;
	private int insideWindowSize;
	private int effectiveInsideNumberOfWindows;
	private BamGenomeWindow currentInsideWindow;
	private HashMap<Long,BamGenomeWindow> openInsideWindows;
	private BamStats insideBamStats;
	private int numberOfInsideMappedReads;
    private int threadNumber;
    private int numReadsInBunch;

	// outside
	private boolean computeOutsideStats;
	private long outsideReferenceSize;
	private int outsideWindowSize;
	private int effectiveOutsideNumberOfWindows;
	private BamGenomeWindow currentOutsideWindow;
	private HashMap<Long,BamGenomeWindow> openOutsideWindows;
	private BamStats outsideBamStats;
	private int numberOfOutsideMappedReads;

	private long[] selectedRegionStarts;
	private long[] selectedRegionEnds;
	private long[] selectedRegionRelativePositions;

	// insert size
	private boolean computeInsertSize;

	// chromosome
	private boolean computeChromosomeStats;
	private BamStats chromosomeStats;
    private BamGenomeWindow currentChromosome;
    private ConcurrentMap<Long,BamGenomeWindow> openChromosomeWindows;
    private HashMap<Long,ContigRecord> contigCache;
    private ArrayList<Integer> chromosomeWindowIndexes;


    private final int maxSizeOfTaskQueue = 100;

	// reporting
	private boolean activeReporting;
	private boolean saveCoverage;
	private boolean isPairedData;
    List<Future<Collection<SingleReadData>>> results;


    private ExecutorService workerThreadPool;

    public BamStatsAnalysis(String bamFile){
		this.bamFile = bamFile;
		this.numberOfWindows = 400;
        this.threadNumber = 4;
        this.numReadsInBunch = 2000;
        this.computeChromosomeStats = false;
        this.outdir = ".";
		logger = new Logger();
        workerThreadPool = Executors.newFixedThreadPool(threadNumber);
        chromosomeWindowIndexes = new ArrayList<Integer>();
    }

    public void run() throws Exception{

        long startTime = System.currentTimeMillis();

        // init reader
        SAMFileReader reader = new SAMFileReader(new File(bamFile));

        // org.bioinfo.ntools.process header
		String lastActionDone = "loading sam header header";
		logger.println(lastActionDone);
		SAMFileHeader header = reader.getFileHeader();

        // load locator
        lastActionDone = "loading locator";
        logger.println(lastActionDone);
        loadLocator(header);

        // load reference
        lastActionDone = "loading reference";
        logger.println(lastActionDone);
        loadReference();

        // init window set
        windowSize = computeWindowSize(referenceSize,numberOfWindows);
        //effectiveNumberOfWindows = computeEffectiveNumberOfWindows(referenceSize,windowSize);
        List<Long> windowPositions = computeWindowPositions(windowSize);
        effectiveNumberOfWindows = windowPositions.size();
        bamStats = new BamStats("genome",referenceSize,effectiveNumberOfWindows);
        logger.println("effectiveNumberOfWindows " + effectiveNumberOfWindows);
        bamStats.setSourceFile(bamFile);
        //bamStats.setWindowReferences("w",windowSize);
        bamStats.setWindowReferences("w", windowPositions);
        openWindows = new ConcurrentHashMap<Long,BamGenomeWindow>();

        currentWindow = nextWindow(bamStats,openWindows,reference,true);


        // chromosome stats
		if(computeChromosomeStats){
			chromosomeStats = new BamStats("chromosomes", referenceSize, numberOfReferenceContigs);
			chromosomeStats.setWindowReferences(locator);
			openChromosomeWindows = new ConcurrentHashMap<Long, BamGenomeWindow>();
			currentChromosome = nextWindow(chromosomeStats,openChromosomeWindows,reference,false);
		    chromosomeStats.activateWindowReporting(outdir + "/" + Constants.NAME_OF_FILE_CHROMOSOMES);
        }

        // init working variables
		isPairedData = true;

		// run reads
        SAMRecordIterator iter = reader.iterator();

        ArrayList<SAMRecord> readsBunch = new ArrayList<SAMRecord>();
        results = new ArrayList<Future<Collection<SingleReadData>>>();

        while(iter.hasNext()){

            SAMRecord read = null;

            try {
                read = iter.next();
            } catch (RuntimeException e) {
                logger.warn( e.getMessage() );
            }

            if (read == null) {
                continue;
            }

			// filter invalid reads
			if(read.isValid() == null){
                 if (read.getDuplicateReadFlag()) {
                    numberOfDuplicatedReads++;
                 }
                 // accumulate only mapped reads
				if(!read.getReadUnmappedFlag()) {
                    numberOfMappedReads++;
                }  else {
                    ++numberOfReads;
                    continue;
                }


			    // compute absolute position
				long position = locator.getAbsoluteCoordinates(read.getReferenceName(),read.getAlignmentStart());

                if (computeChromosomeStats && position > currentChromosome.getEnd()) {
                    collectAnalysisResults(readsBunch);
                    currentChromosome = finalizeAndGetNextWindow(position, currentChromosome,
                            openChromosomeWindows, chromosomeStats, reference, false);
                }

                // finalize current and get next window
			    if(position > currentWindow.getEnd() ){
                    //analyzeReads(readsBunch);
                    collectAnalysisResults(readsBunch);
                    //finalize
				    currentWindow = finalizeAndGetNextWindow(position,currentWindow,openWindows,
                            bamStats,reference,true);
                }

                if (currentWindow == null) {
                    //Some reads are out of reference bounds?
                    break;
                }

                readsBunch.add(read);
                if (readsBunch.size() >= numReadsInBunch) {
                    if (results.size() >= maxSizeOfTaskQueue )  {
                        System.out.println("Max size of task queue is exceeded!");
                        collectAnalysisResults(readsBunch);
                    } else {
                        analyzeReadsBunch(readsBunch);
                    }
                }



                numberOfValidReads++;

            }

            numberOfReads++;

        }

        // close stream
        reader.close();

        if (!readsBunch.isEmpty()) {
            int numWindows = bamStats.getNumberOfWindows();
            long lastPosition = bamStats.getWindowEnd(numWindows - 1) + 1;
            //analyzeReads(readsBunch);
            collectAnalysisResults(readsBunch);
            //finalize
            finalizeAndGetNextWindow(lastPosition,currentWindow,openWindows,bamStats,reference,true);
            finalizeAndGetNextWindow(lastPosition,currentChromosome, openChromosomeWindows,
                    chromosomeStats, reference, false);
        }

        workerThreadPool.shutdown();
        workerThreadPool.awaitTermination(2, TimeUnit.MINUTES);

        long endTime = System.currentTimeMillis();

        logger.println("Number of reads: " + numberOfReads);
        logger.println("Number of mapped reads: " + numberOfMappedReads);
        logger.println("Number of valid reads: " + numberOfValidReads);
        logger.println("Number of dupliated reads: " + numberOfDuplicatedReads);
        logger.println("Time taken to analyze reads: " + (endTime - startTime) / 1000);

        // summarize
		percentageOfValidReads = ((double)numberOfValidReads/(double)numberOfReads)*100.0;
		bamStats.setNumberOfReads(numberOfReads);
		bamStats.setNumberOfMappedReads(numberOfMappedReads);
		bamStats.setPercentageOfMappedReads(((double)numberOfMappedReads/(double)numberOfReads)*100.0);
		bamStats.setPercentageOfValidReads(percentageOfValidReads);

		// compute descriptors
		logger.print("Computing descriptors...");
		bamStats.computeDescriptors();

		// compute histograms
		logger.print("Computing histograms...");
		bamStats.computeCoverageHistogram();

        long overallTime = System.currentTimeMillis();
        logger.println("Overall analysis time: " + (overallTime - startTime) / 1000);



    }

    public static List<SAMRecord> getShallowCopy(List<SAMRecord> list) {
        ArrayList<SAMRecord> result = new ArrayList<SAMRecord>(list.size());

        for (SAMRecord r : list)  {
            result.add(r);
        }

        return result;

    }

    private void analyzeReadsBunch( ArrayList<SAMRecord> readsBunch ) throws ExecutionException, InterruptedException {
         List<SAMRecord> bunch = getShallowCopy(readsBunch);
         Callable<Collection<SingleReadData>> task = new ProcessBunchOfReadsTask(bunch,currentWindow, this);
         Future<Collection<SingleReadData>> result = workerThreadPool.submit(task);
         results.add( result );
         readsBunch.clear();
    }

    private void collectAnalysisResults(ArrayList<SAMRecord> readsBunch) throws InterruptedException, ExecutionException {

        // start last bunch
        analyzeReadsBunch(readsBunch);

        // wait till all tasks are finished
        for (Future<Collection<SingleReadData>> result : results) {
            Collection<SingleReadData> dataset = result.get();
            for (SingleReadData rd : dataset) {
                BamGenomeWindow w = openWindows.get(rd.getWindowStart());
                w.addReadData(rd);
                if (computeChromosomeStats) {
                    currentChromosome.addReadData(rd);
                }
            }
        }

        results.clear();

    }

    /*private BamGenomeWindow getChromosomeWindow(Long pos) {

        BamGenomeWindow chrWindow;

        ContigRecord cr = contigCache.get(pos);
        long startPos = cr.getStart();
        long endPos = cr.getEnd();

        if (openChromosomeWindows.containsKey(startPos)) {
            chrWindow = openChromosomeWindows.get(startPos);
        }else {
            chrWindow = initWindow(cr.getName(), startPos, endPos, reference, false);
            chromosomeStats.incInitializedWindows();
            openChromosomeWindows.put(startPos,chrWindow);
        }

        return chrWindow;
    }

    private void initContigCache() {
        contigCache = new HashMap<Long, ContigRecord>(bamStats.getNumberOfWindows());
        List<ContigRecord> contigRecords = locator.getContigs();
        long[] windowStarts = bamStats.getWindowStarts();
        int i = 0;
        for (long windowStart : windowStarts) {
            ContigRecord contig = contigRecords.get(i);
            if (windowStart > contig.getEnd()) {
                contig = contigRecords.get(++i);
            }
            contigCache.put(windowStart,contig);

        }


    }*/


    public static BamGenomeWindow initWindow(String name,long windowStart,long windowEnd, byte[]reference,
                                             boolean detailed){
		byte[]miniReference = null;
		if(reference!=null) {
			miniReference = new byte[(int)(windowEnd-windowStart+1)];
			miniReference = Arrays.copyOfRange(reference, (int) (windowStart - 1), (int) (windowEnd - 1));
		}

		if(detailed){
			return new BamDetailedGenomeWindow(name,windowStart,windowEnd,miniReference);
		} else {
			return new BamGenomeWindow(name,windowStart,windowEnd,miniReference);
		}
	}


    private static BamGenomeWindow nextWindow(BamStats bamStats, Map<Long,BamGenomeWindow> openWindows,byte[]reference,boolean detailed){
		// init new current
		BamGenomeWindow currentWindow = null;

		if(bamStats.getNumberOfProcessedWindows() < bamStats.getNumberOfWindows()){
			int numProcessed = bamStats.getNumberOfProcessedWindows();
            long windowStart = bamStats.getWindowStart(numProcessed);

			if(windowStart <= bamStats.getReferenceSize()){
				long windowEnd = bamStats.getCurrentWindowEnd();
				if(openWindows.containsKey(windowStart)){
					currentWindow = openWindows.get(windowStart);
					//openWindows.remove(windowStart);
				} else {
					currentWindow = initWindow(bamStats.getCurrentWindowName(),windowStart,Math.min(windowEnd,bamStats.getReferenceSize()),reference,detailed);
					bamStats.incInitializedWindows();
                    openWindows.put(windowStart, currentWindow);
				}
			}
		}

		return currentWindow;
	}

    private BamGenomeWindow finalizeAndGetNextWindow(long position, BamGenomeWindow lastWindow,
                                                     Map<Long,BamGenomeWindow> openWindows,BamStats bamStats,
                                                     byte[]reference, boolean detailed)
            throws CloneNotSupportedException, ExecutionException, InterruptedException {
        // position is still far away
        while(position > lastWindow.getEnd() ) {
            //finalizeWindowInSameThread(lastWindow);
            finalizeWindow(lastWindow, bamStats, openWindows);
            lastWindow = nextWindow(bamStats,openWindows,reference,detailed);
            if (lastWindow == null) {
                break;
            }

        }

        return lastWindow;
    }


    private Future<Integer> finalizeWindow(BamGenomeWindow window, BamStats bamStats,
                                           Map<Long,BamGenomeWindow> openWindows) {
        long windowStart = bamStats.getCurrentWindowStart();
        openWindows.remove(windowStart);
        bamStats.incProcessedWindows();
        return workerThreadPool.submit( new FinalizeWindowTask(bamStats,window));
    }


    private static Integer finalizeWindowInSameThread(BamGenomeWindow window,BamStats bamStats,
                                               Map<Long,BamGenomeWindow> openWindows) {
        long windowStart = bamStats.getCurrentWindowStart();
        openWindows.remove(windowStart);
        bamStats.incProcessedWindows();
        FinalizeWindowTask task = new FinalizeWindowTask(bamStats,window);
        return task.call();
    }


    private void loadLocator(SAMFileHeader header){
		locator = new GenomeLocator();
		for(int i=0; i<header.getSequenceDictionary().getSequences().size(); i++){
			locator.addContig(header.getSequence(i).getSequenceName(), header.getSequence(i).getSequenceLength());
		}
	}


	private void loadReference() throws Exception{
		if(referenceAvailable){
			// prepare reader
			FastaReader reader = new FastaReader(referenceFile);

			// init buffer
			StringBuilder referenceBuffer = new StringBuilder();

			numberOfReferenceContigs = 0;
			Fasta contig;
			while((contig=reader.read())!=null){
				referenceBuffer.append(contig.getSeq().toUpperCase());
				numberOfReferenceContigs++;
			}

			reader.close();
			reference = referenceBuffer.toString().getBytes();
			referenceSize = reference.length;
			if(reference.length!=locator.getTotalSize()){
				throw new Exception("invalid reference file, number of nucleotides differs");
			}
		} else {
			referenceSize = locator.getTotalSize();
			numberOfReferenceContigs = locator.getContigs().size();
		}
	}


    private int computeWindowSize(long referenceSize, int numberOfWindows){
		int windowSize = (int)Math.floor((double)referenceSize/(double)numberOfWindows);
		if(((double)referenceSize/(double)numberOfWindows)>windowSize){
			windowSize++;
		}

		return windowSize;
	}


	private int computeEffectiveNumberOfWindows(long referenceSize, int windowSize){
		int numberOfWindows = (int)Math.floor((double)referenceSize/(double)windowSize);
		if(((double)referenceSize/(double)windowSize)>numberOfWindows){
			numberOfWindows++;
		}

		return numberOfWindows;
	}

    private ArrayList<Long> computeWindowPositions(int windowSize){
        List<ContigRecord> contigs = locator.getContigs();
        ArrayList<Long> windowStarts = new ArrayList<Long>();

        long startPos = 1;
        int i = 0;
        while (startPos < referenceSize) {
            windowStarts.add(startPos);
            startPos += windowSize;
            long nextContigStart = contigs.get(i).getEnd() + 1;
            if (startPos >= nextContigStart) {
                if (startPos > nextContigStart) {
                    System.out.println("Chromosome window break: " + (windowStarts.size() + 1));
                    windowStarts.add(nextContigStart);
                }
                chromosomeWindowIndexes.add(i);
                i++;
            }
        }
        return windowStarts;
    }


    public GenomeLocator getLocator() {
        return locator;
    }

    byte[] getReference() {
        return reference;
    }

    public BamStats getBamStats() {
        return bamStats;
    }

    ConcurrentMap<Long,BamGenomeWindow> getOpenWindows() {
        return openWindows;
    }

    public boolean isPairedData() {
        return isPairedData;
    }

    public void setNumberOfWindows(int windowsNum) {
        numberOfWindows = windowsNum;
    }

    public void activeReporting(String outdir){
		this.outdir = outdir;
		this.activeReporting = true;
	}

    public void setComputeChromosomeStats(boolean computeChromosomeStats) {
        this.computeChromosomeStats = computeChromosomeStats;
    }

}
