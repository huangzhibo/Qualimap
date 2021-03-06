/**
 * QualiMap: evaluation of next generation sequencing alignment data
 * Copyright (C) 2016 Garcia-Alcalde et al.
 * http://qualimap.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.bioinfo.ngs.qc.qualimap.process;

import net.sf.samtools.*;
import net.sf.samtools.util.RuntimeIOException;
import org.bioinfo.commons.log.Logger;
import org.bioinfo.formats.core.sequence.Fasta;
import org.bioinfo.formats.core.sequence.io.FastaReader;
import org.bioinfo.formats.exception.FileFormatException;
import org.bioinfo.ngs.qc.qualimap.beans.*;
import org.bioinfo.ngs.qc.qualimap.beans.BamDetailedGenomeWindow;
import org.bioinfo.ngs.qc.qualimap.beans.BamGenomeWindow;
import org.bioinfo.ngs.qc.qualimap.beans.BamStats;
import org.bioinfo.ngs.qc.qualimap.beans.ContigRecord;
import org.bioinfo.ngs.qc.qualimap.beans.GenomeLocator;
import org.bioinfo.ngs.qc.qualimap.common.Constants;
import org.bioinfo.ngs.qc.qualimap.common.LibraryProtocol;
import org.bioinfo.ngs.qc.qualimap.common.*;
import org.bioinfo.ngs.qc.qualimap.common.CommandLineBuilder;
import org.bioinfo.ngs.qc.qualimap.gui.utils.StatsKeeper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by  kokonech
 * Date: 11/7/11
 * Time: 4:06 PM
 */
public class BamStatsAnalysis {

    // input alignment
	private String bamFile;

	// reference
    private String  referenceFile;
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
	private long numberOfReads;
	private long numberOfValidReads;
    private long numberOfSecondaryAlignments;
	private long numberOfDuplicatesSkipped;
    private long numberOfCorrectStrandReads;
    private long numberOfProblematicReads;
    private long numberOfReadsWithStartGreatThenEnd;

	// statistics
	private BamStats bamStats;

	private Logger logger;
    private LoggerThread loggerThread;

	// working variables
	private BamGenomeWindow currentWindow;
	private ConcurrentMap<Long,BamGenomeWindow> openWindows;
    private int threadNumber;
    private int numReadsInBunch;
    private int progress;
    private int minHomopolymerSize;

	// nucleotide reporting
	private String outdir;

	// gff support
	private boolean selectedRegionsAvailable;
	private String featureFile;
    private int numberOfSelectedRegions;

	// inside
	private long insideReferenceSize;
    private boolean skipMarkedDuplicates, skipDetectedDuplicates;
    private boolean collectIntersectingPairedEndReads;

	// outside
	private boolean computeOutsideStats;
	private BamGenomeWindow currentOutsideWindow;
	private HashMap<Long,BamGenomeWindow> openOutsideWindows;
    private BamStats outsideBamStats;

    // read size
    long acumReadSize;
    int maxReadSize;
    int minReadSize;

    //regions
	private long[] selectedRegionStarts;
	private long[] selectedRegionEnds;
    RegionOverlapLookupTable regionOverlapLookupTable;
    LibraryProtocol protocol;

    BamStatsCollector bamStatsCollector;
    BamStatsCollector outsideBamStatsCollector;

	// chromosome
	private ArrayList<Integer> chromosomeWindowIndexes;

    private int maxSizeOfTaskQueue;


	// reporting
	private boolean activeReporting;
	private boolean saveCoverage, nonZeroCoverageOnly;
    private String pathToCoverageReport;
    private int windowBlockSizeToReport;


    // analysis
	private boolean isPairedData;
    List<Future<ProcessBunchOfReadsTask.Result>> results;
    Future<Integer> finalizeWindowResult;
    long timeToCalcOverlappers;
    private String pgProgram, pgCommandString;

    public static final String WARNING_ID_CHROMOSOME_NOT_FOUND = "Some regions are not loaded";
    public static final String WARNING_ID_NO_MAPPED_READS = "NO MAPPED READS";
    public static final String WARNING_NO_MARKED_DUPLICATES = "No flagged duplicates are detected";

    public static final String HUMAN_GENOME_ID = "HUMAN (hg19)";
    public static final String MOUSE_GENOME_ID =  "MOUSE (mm9)";
    public static final String MM10_GENOME_ID =  "MOUSE (mm10)";
    public static final String HUMAN_GENOME_NAME = "human";
    public static final String MOUSE_GENOME_NAME = "mouse";

    private static Map<String,String> genomeGcContentMap;

    public static synchronized Map<String, String> getGcContentFileMap() {

        if (genomeGcContentMap == null) {
            genomeGcContentMap = new HashMap<String,String>();
            genomeGcContentMap.put(HUMAN_GENOME_ID, "species/human.hg19.gc_histogram.txt");
            genomeGcContentMap.put(MOUSE_GENOME_ID, "species/mouse.mm9.gc_histogram.txt");
            genomeGcContentMap.put(MM10_GENOME_ID, "species/mouse.mm10.gc_histogram.txt");
        }

        return genomeGcContentMap;
    }

    private ExecutorService workerThreadPool;

    public BamStatsAnalysis(String bamFile) {
		this.bamFile = bamFile;
		this.numberOfWindows = Constants.DEFAULT_NUMBER_OF_WINDOWS;
        this.numReadsInBunch = Constants.DEFAULT_CHUNK_SIZE;
        this.minHomopolymerSize = Constants.DEFAULT_HOMOPOLYMER_SIZE;
        this.maxSizeOfTaskQueue = 10;
        this.minReadSize = Integer.MAX_VALUE;
        this.threadNumber = 4;
        this.selectedRegionsAvailable =false;
        this.computeOutsideStats = false;
        this.skipMarkedDuplicates = false;
        this.skipDetectedDuplicates = false;
        this.collectIntersectingPairedEndReads = false;
        this.outdir = ".";
        this.saveCoverage = false;
        this.nonZeroCoverageOnly = true;
        this.windowBlockSizeToReport = 50;
        protocol = LibraryProtocol.NON_STRAND_SPECIFIC;
        pgProgram = "";
        pgCommandString = "";
		logger = new Logger();
        chromosomeWindowIndexes = new ArrayList<Integer>();
    }

    public void run() throws Exception{

        long startTime = System.currentTimeMillis();

        workerThreadPool = Executors.newFixedThreadPool(threadNumber);

        SAMFileReader reader = new SAMFileReader(new File(bamFile));
        reader.setValidationStringency(SAMFileReader.ValidationStringency.SILENT);

        // org.bioinfo.ntools.process header
		String lastActionDone = "Loading sam header...";
		logger.println(lastActionDone);
		SAMFileHeader header = reader.getFileHeader();


        String textHeader = header.getTextHeader();
        if (textHeader != null && !textHeader.isEmpty()) {
            if (textHeader.contains("@HD")){
                try {
                    SAMFileHeader.SortOrder sortOrder = header.getSortOrder();
                    if (sortOrder != SAMFileHeader.SortOrder.coordinate) {
                        logger.warn("According to header the BAM file is not sorted by coordinate!");
                    }
                } catch (IllegalArgumentException ex) {
                    logger.warn("Non-standard header SortOrder value!");
                }
            }else {
                logger.warn("@HD line is not presented in the BAM file header.");
            }
        }

        // load locator
        lastActionDone = "Loading locator...";
        logger.println(lastActionDone);
        loadLocator(header);
        loadProgramRecords(header.getProgramRecords());

        // load reference
        lastActionDone = "Loading reference...";
        logger.println(lastActionDone);
        loadReference();

        // init window set
        windowSize = computeWindowSize(referenceSize,numberOfWindows);
        //effectiveNumberOfWindows = computeEffectiveNumberOfWindows(referenceSize,windowSize);
        List<Long> windowPositions = computeWindowPositions(windowSize);
        effectiveNumberOfWindows = windowPositions.size();

        if (effectiveNumberOfWindows > Constants.DEFAULT_STABLIZED_WINDOW_PROPORTION) {
            windowBlockSizeToReport = effectiveNumberOfWindows / 10;
        }

        if (skipDetectedDuplicates || skipMarkedDuplicates) {
            String skipDuplicatesReport = "";
            if (skipDetectedDuplicates && skipMarkedDuplicates) {
                skipDuplicatesReport += "Both flagged and estimated by Qualimap algorithm duplicate alignments will be skipped...";
            } else if (skipMarkedDuplicates) {
                skipDuplicatesReport += "Only flagged duplicate alignments will be skipped...";
            } else {
                skipDuplicatesReport += "Only duplicate alignments estimated by Qualimap algorithm will be skipped...";
            }

            logger.println(skipDuplicatesReport);
        }



        bamStats = new BamStats("genome", locator, referenceSize,effectiveNumberOfWindows);
        logger.println("Number of windows: " + numberOfWindows +
                ", effective number of windows: " + effectiveNumberOfWindows);
        logger.println("Chunk of reads size: " + numReadsInBunch);
        logger.println("Number of threads: " + threadNumber);
        bamStats.setSourceFile(bamFile);
        //bamStats.setWindowReferences("w",windowSize);
        bamStats.setWindowReferences("w", windowPositions);
        bamStatsCollector = new BamStatsCollector();
        if (collectIntersectingPairedEndReads) {
            bamStatsCollector.enableIntersectingReadsCollection();
        }

        openWindows = new ConcurrentHashMap<Long,BamGenomeWindow>();

        if (saveCoverage) {
            bamStats.activateCoverageReporting(pathToCoverageReport, nonZeroCoverageOnly);
        }

        //regions
        if(selectedRegionsAvailable){

			// load selected regions
            loadSelectedRegions();
            outsideBamStatsCollector = new BamStatsCollector();

            // outside of regions stats
            if (computeOutsideStats) {
                outsideBamStats = new BamStats("outside", locator, referenceSize, effectiveNumberOfWindows);
                outsideBamStats.setSourceFile(bamFile);
                outsideBamStats.setWindowReferences("out_w", windowPositions);
                openOutsideWindows = new HashMap<Long,BamGenomeWindow>();
                currentOutsideWindow = nextWindow(outsideBamStats,openOutsideWindows,reference,true);

                if(activeReporting) {
                    outsideBamStats.activateWindowReporting(outdir + "/outside_window.txt");
                }

                if(saveCoverage){
                    outsideBamStats.activateCoverageReporting(outdir + "/outside_coverage.txt", nonZeroCoverageOnly);
                }

                // we have twice more data from the bunch, so the queue is limited now
                maxSizeOfTaskQueue /= 2;
            }

		}

        currentWindow = nextWindow(bamStats, openWindows, reference, true);

        // run reads
        SAMRecordIterator iter = reader.iterator();

        ArrayList<SAMRecord> readsBunch = new ArrayList<SAMRecord>();
        results = new ArrayList<Future<ProcessBunchOfReadsTask.Result>>();

        timeToCalcOverlappers = 0;

        while(iter.hasNext()){

            SAMRecord read = null;

            try {
                read = iter.next();
            } catch (RuntimeException e) {
                numberOfProblematicReads++;
            }

            if (read == null) {
                continue;
            }

            // compute absolute position
            long position = locator.getAbsoluteCoordinates(read.getReferenceName(),read.getAlignmentStart());

            //compute read size
            int readSize = read.getReadLength();
            acumReadSize += readSize;
            if (readSize > maxReadSize) {
                maxReadSize = readSize;
            }
            if (readSize < minReadSize) {
                minReadSize = readSize;
            }

            // filter invalid reads
            List<SAMValidationError> valErrorList = read.isValid();
            boolean readIsValid = valErrorList == null;
            if (!readIsValid) {
                if (valErrorList.size() == 1) {
                    readIsValid = valErrorList.get(0).getType() == SAMValidationError.Type.READ_GROUP_NOT_FOUND;
                }
            }

            if (read.getNotPrimaryAlignmentFlag()) {
                ++numberOfSecondaryAlignments;
                continue;
            }

            boolean novelRead = (read.getFlags() & Constants.SAM_FLAG_SUPP_ALIGNMENT) == 0;
            if (novelRead) {
                ++numberOfReads;
            }

			if(readIsValid){

                // accumulate only mapped reads
				if(read.getReadUnmappedFlag()) {
                    continue;
                }

                int insertSize = 0;
                if (read.getReadPairedFlag()) {
                    insertSize = read.getInferredInsertSize();
                }

                if (position < currentWindow.getStart()) {
                    throw new RuntimeException("The alignment file is unsorted.\n" +
                            "Please sort the BAM file by coordinate.");
                }

                long findOverlappersStart = System.currentTimeMillis();

                if (selectedRegionsAvailable) {
                    //boolean readOverlapsRegions = readOverlapsRegions(position, position + read.getReadLength() - 1);
                    boolean readOverlapsRegions = readOverlapsRegions(read);

                    if (readOverlapsRegions) {
                        if (bamStatsCollector.updateStats(read) && skipMarkedDuplicates) {
                            numberOfDuplicatesSkipped++;
                            continue;
                        }

                        read.setAttribute(Constants.READ_IN_REGION, 1);
                        if (bamStats.updateReadStartsHistogram(position) && skipDetectedDuplicates ) {
                            numberOfDuplicatesSkipped++;
                            continue;
                        }
                        if (collectIntersectingPairedEndReads) {
                            bamStatsCollector.collectPairedReadInfo(read);
                        }
                        bamStats.updateInsertSizeHistogram(insertSize);
                    } else {
                        read.setAttribute(Constants.READ_IN_REGION, 0);
                        if (outsideBamStatsCollector.updateStats(read) && skipMarkedDuplicates) {
                            numberOfDuplicatesSkipped++;
                            continue;
                        }
                        if (computeOutsideStats) {
                            if (outsideBamStats.updateReadStartsHistogram(position) && skipDetectedDuplicates) {
                                numberOfDuplicatesSkipped++;
                                continue;
                            }
                            outsideBamStats.updateInsertSizeHistogram(insertSize);
                        }
                    }
                } else {
                    read.setAttribute(Constants.READ_IN_REGION, 1);
                    if (bamStatsCollector.updateStats(read) && skipMarkedDuplicates) {
                        numberOfDuplicatesSkipped++;
                        continue;
                    }
                    if (bamStats.updateReadStartsHistogram(position) && skipDetectedDuplicates) {
                        numberOfDuplicatesSkipped++;
                        continue;
                    }
                    if (collectIntersectingPairedEndReads) {
                        bamStatsCollector.collectPairedReadInfo(read);

                    }
                    bamStats.updateInsertSizeHistogram(insertSize);
                }

                timeToCalcOverlappers += System.currentTimeMillis() - findOverlappersStart;

                // finalize current and get next window
			    if(position > currentWindow.getEnd() ){
                    //analyzeReads(readsBunch);
                    collectAnalysisResults(readsBunch);
                    //finalize
				    currentWindow = finalizeAndGetNextWindow(position, currentWindow, openWindows,
                            bamStats, reference, true);

                    if (selectedRegionsAvailable && computeOutsideStats) {
                        currentOutsideWindow.inverseRegions();
                        currentOutsideWindow = finalizeAndGetNextWindow(position, currentOutsideWindow,
                                openOutsideWindows, outsideBamStats, reference, true);
                    }

                }

                if (currentWindow == null) {
                    //Some reads are out of reference bounds?
                    break;
                }

                readsBunch.add(read);
                if (readsBunch.size() >= numReadsInBunch) {
                    if (results.size() >= maxSizeOfTaskQueue )  {
                        //System.out.println("Max size of task queue is exceeded!");
                        collectAnalysisResults(readsBunch);
                    } else {
                        analyzeReadsBunch(readsBunch);
                    }
                }

                numberOfValidReads++;

            } else {
                numberOfProblematicReads++;
            }

        }

        // close stream
        reader.close();

        if (!readsBunch.isEmpty() || bamStats.getNumberOfProcessedWindows() < bamStats.getNumberOfWindows() ) {
            int numWindows = bamStats.getNumberOfWindows();
            long lastPosition = bamStats.getWindowEnd(numWindows - 1) + 1;
            collectAnalysisResults(readsBunch);
            //finalize
            finalizeAndGetNextWindow(lastPosition, currentWindow, openWindows, bamStats, reference, true);
            if (selectedRegionsAvailable && computeOutsideStats) {
                currentOutsideWindow.inverseRegions();
                finalizeAndGetNextWindow(lastPosition,currentOutsideWindow, openOutsideWindows,
                                outsideBamStats, reference, true);
            }
        }

        workerThreadPool.shutdown();
        workerThreadPool.awaitTermination(2, TimeUnit.MINUTES);

        long endTime = System.currentTimeMillis();

        logger.println("Total processed windows:" + bamStats.getNumberOfProcessedWindows());
        logger.println("Number of reads: " + numberOfReads);
        logger.println("Number of valid reads: " + numberOfValidReads);
        logger.println("Number of correct strand reads:" + numberOfCorrectStrandReads);

        if (numberOfReadsWithStartGreatThenEnd > 0) {
            logger.warn( numberOfReadsWithStartGreatThenEnd +
                    "  read alignments have start greater than end" );
        }

        if (numberOfProblematicReads > 0) {
            logger.warn("SAMRecordParser marked " + numberOfProblematicReads + " problematic reads.");
        }

        if (collectIntersectingPairedEndReads) {
            bamStatsCollector.finalizeAlignmentInfo();
        }

        logger.println("\nInside of regions...");
        logger.print(bamStatsCollector.report());

        if (computeOutsideStats) {
            logger.println("\nOuside of regions...");
            logger.print(outsideBamStatsCollector.report());
        }

        logger.println("Time taken to analyze reads: " + (endTime - startTime) / 1000);
        logger.println();

        if (numberOfReads == 0) {
            throw new RuntimeException("The BAM file is empty or corrupt");
        }

        bamStats.setNumberOfReads(numberOfReads);
        bamStats.setNumDuplicatesSkipped(numberOfDuplicatesSkipped);
        bamStats.setSkipDuplicatesMode(skipMarkedDuplicates, skipDetectedDuplicates);
        bamStats.setNumberOfSecondaryAlignments(numberOfSecondaryAlignments);
        if (skipMarkedDuplicates && numberOfDuplicatesSkipped == 0) {
            bamStats.addWarning(WARNING_NO_MARKED_DUPLICATES,
                "Make sure duplicate alignments are flagged in the BAM file or apply a different skip duplicates mode.");
        }

        long totalNumberOfMappedReads = bamStatsCollector.getNumMappedReads();
        long totalNumberOfPairedReads = bamStatsCollector.getNumPairedReads();
        long totalNumberOfMappedFirstOfPair = bamStatsCollector.getNumMappedFirstInPair();
        long totalNumberOfMappedSecondOfPair = bamStatsCollector.getNumMappedSecondInPair();
        long totalNumberOfSingletons = bamStatsCollector.getNumSingletons();
        long totalNumberOfSuppAlignments = bamStatsCollector.getNumSupplementaryAlignments();

        boolean mappedReadsInRegion = totalNumberOfMappedReads > 0;

        if (selectedRegionsAvailable) {

            bamStats.setNumSelectedRegions(numberOfSelectedRegions);
            if (computeOutsideStats)  {
                // Size was calculated two times during the analysis
                insideReferenceSize /= 2;
            }
            bamStats.setInRegionReferenceSize(insideReferenceSize);

            // update totals
            totalNumberOfMappedReads  += outsideBamStatsCollector.getNumMappedReads();
            totalNumberOfPairedReads += outsideBamStatsCollector.getNumPairedReads();
            totalNumberOfMappedFirstOfPair += outsideBamStatsCollector.getNumMappedFirstInPair();
            totalNumberOfMappedSecondOfPair += outsideBamStatsCollector.getNumMappedSecondInPair();
            totalNumberOfSingletons += outsideBamStatsCollector.getNumSingletons();

            // inside of regions
            bamStats.setNumberOfMappedReadsInRegions(bamStatsCollector.getNumMappedReads());
            bamStats.setNumberOfPairedReadsInRegions(bamStatsCollector.getNumPairedReads());
            bamStats.setNumberOfMappedFirstOfPairInRegions(bamStatsCollector.getNumMappedFirstInPair());
            bamStats.setNumberOfMappedSecondOfPairInRegions(bamStatsCollector.getNumMappedSecondInPair());
            bamStats.setNumberOfSingletonsInRegions(bamStatsCollector.getNumSingletons());
            bamStats.setNumberOfCorrectStrandReads(numberOfCorrectStrandReads);
        }

        bamStats.setNumberOfMappedReads(totalNumberOfMappedReads);
        bamStats.setNumberOfPairedReads(totalNumberOfPairedReads);
        bamStats.setNumberOfMappedFirstOfPair(totalNumberOfMappedFirstOfPair);
        bamStats.setNumberOfMappedSecondOfPair(totalNumberOfMappedSecondOfPair);
        bamStats.setNumberOfSingletons( totalNumberOfSingletons );
        bamStats.setNumberOfSuppAlignments( totalNumberOfSuppAlignments);
        if (collectIntersectingPairedEndReads) {
            bamStats.setNumberOfIntersectingReadPairs(bamStatsCollector.getNumOverlappingReadPairs(),
                    bamStatsCollector.getNumOverlappingBases());
        }

        bamStats.setReferenceSize(referenceSize);
        bamStats.setNumberOfReferenceContigs(locator.getContigs().size());
        bamStats.setReadMaxSize(maxReadSize);
        bamStats.setReadMinSize(minReadSize);
        bamStats.setReadMeanSize( acumReadSize / (double) numberOfReads );

        bamStats.setNumMarkedDuplcateReads( bamStatsCollector.getNumMarkedDuplicates() );

        isPairedData = bamStats.getNumberOfPairedReads() > 0 &&
                (bamStats.getNumberOfPairedReads() - bamStats.getNumberOfSingletons()) > 0;

        if (mappedReadsInRegion) {
            logger.println("Computing descriptors...");
		    bamStats.computeDescriptors();
            logger.println("Computing per chromosome statistics...");
		    bamStats.computeChromosomeStats(locator, chromosomeWindowIndexes);
            logger.println("Computing histograms...");
		    bamStats.computeHistograms();
        } else {
            logger.println("\nWARNING: number of mapped reads equals zero");
            bamStats.addWarning(WARNING_ID_NO_MAPPED_READS, "Total number of mapped reads or mapped reads in region equals zero.\n" +
                            "For more details, check the number of Unmapped reads.");
        }

        if(selectedRegionsAvailable && computeOutsideStats){

            outsideBamStats.setReferenceSize(referenceSize);
            outsideBamStats.setNumberOfReferenceContigs(locator.getContigs().size());
            outsideBamStats.setNumSelectedRegions(numberOfSelectedRegions);
            outsideBamStats.setInRegionReferenceSize(referenceSize - insideReferenceSize);
            outsideBamStats.setNumberOfReads(numberOfReads);
            outsideBamStats.setNumberOfSecondaryAlignments(numberOfSecondaryAlignments);

            outsideBamStats.setNumberOfMappedReads(totalNumberOfMappedReads);
            outsideBamStats.setNumberOfPairedReads(totalNumberOfPairedReads);
            outsideBamStats.setNumberOfMappedFirstOfPair(totalNumberOfMappedFirstOfPair);
            outsideBamStats.setNumberOfMappedSecondOfPair(totalNumberOfMappedSecondOfPair);
            outsideBamStats.setNumberOfSingletons(totalNumberOfSingletons);

            outsideBamStats.setNumberOfMappedReadsInRegions(outsideBamStatsCollector.getNumMappedReads());
            outsideBamStats.setNumberOfPairedReadsInRegions(outsideBamStatsCollector.getNumPairedReads());
            outsideBamStats.setNumberOfMappedFirstOfPairInRegions(outsideBamStatsCollector.getNumMappedFirstInPair());
            outsideBamStats.setNumberOfMappedSecondOfPairInRegions(outsideBamStatsCollector.getNumMappedSecondInPair());
            outsideBamStats.setNumberOfSingletonsInRegions(outsideBamStatsCollector.getNumSingletons());

            outsideBamStats.setReadMaxSize(maxReadSize);
            outsideBamStats.setReadMinSize(minReadSize);
            outsideBamStats.setReadMeanSize( acumReadSize / (double) numberOfReads );

            outsideBamStats.setNumMarkedDuplcateReads( outsideBamStatsCollector.getNumMarkedDuplicates() );

            if (outsideBamStatsCollector.getNumMappedReads() > 0)  {
                logger.println("Computing descriptors for outside regions...");
                outsideBamStats.computeDescriptors();
                logger.println("Computing per chromosome statistics for outside regions...");
                outsideBamStats.computeChromosomeStats(locator, chromosomeWindowIndexes);
                logger.println("Computing histograms for outside regions...");
                outsideBamStats.computeHistograms();
            } else  {
                logger.println("\nWARNING: number of mapped reads outside of regions equals zero");
                bamStats.addWarning(WARNING_ID_NO_MAPPED_READS, "Number of mapped reads outside of regions equals zero.\n" +
                    "For more details, check the number of Unmapped reads.");
            }

        }

        long overallTime = System.currentTimeMillis();
        logger.println("Overall analysis time: " + (overallTime - startTime) / 1000);

    }

    private void loadProgramRecords(List<SAMProgramRecord> programRecords) {
        if (!programRecords.isEmpty()) {
            SAMProgramRecord rec = programRecords.get(0);
            pgProgram = rec.getProgramGroupId();
            String ver = rec.getProgramVersion();
            if (ver != null ) {
                pgProgram += " (" + ver + ")";
            }
            String cmd = rec.getCommandLine();
            if (cmd != null ) {
                pgCommandString = cmd;
            }
        }
    }

    private static List<SAMRecord> getShallowCopy(List<SAMRecord> list) {
        ArrayList<SAMRecord> result = new ArrayList<SAMRecord>(list.size());

        for (SAMRecord r : list)  {
            result.add(r);
        }

        return result;

    }

    private void analyzeReadsBunch( ArrayList<SAMRecord> readsBunch ) throws ExecutionException, InterruptedException {
         List<SAMRecord> bunch = getShallowCopy(readsBunch);
         Callable<ProcessBunchOfReadsTask.Result> task = new ProcessBunchOfReadsTask(bunch,currentWindow, this);
         Future<ProcessBunchOfReadsTask.Result> result = workerThreadPool.submit(task);
         results.add( result );
         readsBunch.clear();
    }

    private void collectAnalysisResults(ArrayList<SAMRecord> readsBunch) throws InterruptedException, ExecutionException {

        // start last bunch
        analyzeReadsBunch(readsBunch);

        // wait till all tasks are finished
        for (Future<ProcessBunchOfReadsTask.Result> result : results) {
            ProcessBunchOfReadsTask.Result taskResult = result.get();
            Collection<SingleReadData> dataset = taskResult.getReadAlignmentData();
            for (SingleReadData rd : dataset) {
                BamGenomeWindow w = openWindows.get(rd.getWindowStart());
                w.addReadAlignmentData(rd);
            }
            bamStats.addReadStatsData( taskResult.getReadStatsCollector() );

            if (selectedRegionsAvailable && computeOutsideStats) {
                Collection<SingleReadData> outsideData = taskResult.getOutOfRegionReadsData();
                for (SingleReadData rd : outsideData) {
                    BamGenomeWindow w = openOutsideWindows.get( rd.getWindowStart() );
                    w.addReadAlignmentData(rd);
                }
                outsideBamStats.addReadStatsData( taskResult.getOutRegionReadStatsCollector() );

            }
        }

        results.clear();

    }


    public BamGenomeWindow getOpenWindow(long windowStart,BamStats bamStats,
                                         Map<Long,BamGenomeWindow> openWindows) {
        BamGenomeWindow window;
        if(openWindows.containsKey(windowStart)){
            window = openWindows.get(windowStart);
        } else {
            int numInitWindows = bamStats.getNumberOfInitializedWindows();
            String windowName = bamStats.getWindowName(numInitWindows);
            long windowEnd = bamStats.getWindowEnd(numInitWindows);
            window = initWindow(windowName, windowStart,
                    Math.min(windowEnd, bamStats.getReferenceSize()), reference, true);
            bamStats.incInitializedWindows();
            openWindows.put(windowStart,window);
        }

        return window;
    }

    //TODO: try using this method for better performance
    /*private void calculateRegionsLookUpTableForWindowNew(BamGenomeWindow w) {

        int windowSize = (int) w.getWindowSize();

        BitSet bitSet = new BitSet((int)w.getWindowSize());

        ContigRecord windowContig = locator.getContigCoordinates(w.getStart());

        int relativeWindowStart = (int) windowContig.getRelative();
        int relativeWindowEnd = relativeWindowStart + windowSize - 1;
        String contigName = windowContig.getName();

        regionOverlapLookupTable.markIntersectingRegions(bitSet, relativeWindowStart,
                relativeWindowEnd, contigName);

        w.setSelectedRegions(bitSet);
        w.setSelectedRegionsAvailable(true);
    }*/


    private void calculateRegionsLookUpTableForWindow(BamGenomeWindow w) {

        long windowStart = w.getStart();
        long windowEnd = w.getEnd();


        BitSet bitSet = new BitSet((int)w.getWindowSize());

        int numRegions = selectedRegionStarts.length;

        for (int i = 0; i < numRegions; ++i) {
            long regionStart = selectedRegionStarts[i];
            long regionEnd = selectedRegionEnds[i];

            if (regionStart == -1) {
                continue;
            }

            if ( regionStart >= windowStart && regionStart <= windowEnd ) {
                //System.out.println("Have match! Type1 " + w.getName());
                long end = Math.min(windowEnd,regionEnd);
                bitSet.set((int)(regionStart-windowStart), (int)(end-windowStart + 1),true);
            } else if (regionEnd >= windowStart && regionEnd <= windowEnd) {
                //System.out.println("Have match! Type2 " + w.getName());
                bitSet.set(0, (int)(regionEnd - windowStart + 1), true);
            } else if (regionStart <= windowStart && regionEnd >= windowEnd) {
                //System.out.println("Have match! Type3 " + w.getName());
                bitSet.set(0, (int)(windowEnd - windowStart + 1),true);
            }

        }

        w.setSelectedRegions(bitSet);
        w.setSelectedRegionsAvailable(true);

        insideReferenceSize += bitSet.cardinality();

    }



    public BamGenomeWindow initWindow(String name,long windowStart,long windowEnd, byte[]reference,
                                             boolean detailed){


        //System.err.print("Init window " + name + "\n");
        /*if (name.equals("w_464")) {
            System.err.print("Last window is here?");
        }*/

        byte[]miniReference = null;
		if(reference != null) {
			miniReference = Arrays.copyOfRange(reference, (int) (windowStart - 1), (int) (windowEnd - 1));
		}

        BamGenomeWindow w = detailed ? new BamDetailedGenomeWindow(name,windowStart,windowEnd,miniReference) :
                    new BamGenomeWindow(name,windowStart,windowEnd,miniReference);

        if (selectedRegionsAvailable) {
            calculateRegionsLookUpTableForWindow(w);
        }

        return w;
	}


    private BamGenomeWindow nextWindow(BamStats bamStats, Map<Long,BamGenomeWindow> openWindows,byte[]reference,boolean detailed){
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
            updateProgress();
            //finalizeWindowInSameThread(lastWindow, bamStats, openWindows);
            finalizeWindow(lastWindow, bamStats, openWindows);
            lastWindow = nextWindow(bamStats,openWindows,reference,detailed);
            if (lastWindow == null) {
                break;
            }

        }

        return lastWindow;
    }


    private void updateProgress() {
        if (!computeOutsideStats) {
            progress = (bamStats.getNumberOfProcessedWindows() * 100) / effectiveNumberOfWindows;
        } else {
            progress = ((bamStats.getNumberOfProcessedWindows() +
                    outsideBamStats.getNumberOfProcessedWindows()) * 50) / (effectiveNumberOfWindows);
        }
        if (loggerThread != null) {
            loggerThread.updateProgress(progress);
        }

    }
    private void finalizeWindow(BamGenomeWindow window, BamStats bamStats,
                                           Map<Long,BamGenomeWindow> openWindows) throws ExecutionException, InterruptedException {
        if (finalizeWindowResult != null) {
            // We only run finalization of one window in parallel to prevent to many open windows
            finalizeWindowResult.get();

        }


        long windowStart = bamStats.getCurrentWindowStart();
        openWindows.remove(windowStart);
        bamStats.incProcessedWindows();

        // report progress
        int numProcessedWindows = bamStats.getNumberOfProcessedWindows();
        if (numProcessedWindows % windowBlockSizeToReport == 0 && bamStats == this.bamStats) {
            logger.println("Processed " + numProcessedWindows + " out of " + effectiveNumberOfWindows + " windows...");
        }

        //System.out.println("Time taken to count overlappers: " + timeToCalcOverlappers);
        timeToCalcOverlappers = 0;
        finalizeWindowResult = workerThreadPool.submit( new FinalizeWindowTask(bamStats,window));

    }

    // For debug purposes
    /*private static Integer finalizeWindowInSameThread(BamGenomeWindow window,BamStats bamStats,
                                               Map<Long,BamGenomeWindow> openWindows) {
        long windowStart = bamStats.getCurrentWindowStart();
        openWindows.remove(windowStart);
        bamStats.incProcessedWindows();
        FinalizeWindowTask task = new FinalizeWindowTask(bamStats,window);
        return task.call();
    }*/


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

    private void loadSelectedRegions() throws SecurityException, IOException, NoSuchMethodException, FileFormatException {


        FeatureFileFormat featureFileFormat = DocumentUtils.guessFeaturesFileFormat(featureFile);

        if (featureFileFormat == FeatureFileFormat.UNKNOWN) {
            throw new RuntimeIOException("Unknown feature file format. Please provide file in GFF/GTF or BED format.");
        }

        if (featureFileFormat == FeatureFileFormat.GTF) {
            // while not interested in GTF-specific information we save some memory by using GFF reader
            featureFileFormat = FeatureFileFormat.GFF;
        }

		// init gff reader
		numberOfSelectedRegions = 0;
		GenomicFeatureStreamReader featureFileReader = new GenomicFeatureStreamReader(featureFile, featureFileFormat);
		System.out.println("Initializing regions from " + featureFile + ".....");
		while(featureFileReader.skipNextRecord()){
			numberOfSelectedRegions++;
		}
		if (numberOfSelectedRegions == 0) {
            throw new RuntimeException("Failed to load selected regions.");
        }
        System.out.println("Found " + numberOfSelectedRegions + " regions");

        selectedRegionStarts = new long[numberOfSelectedRegions];
		selectedRegionEnds = new long[numberOfSelectedRegions];
        regionOverlapLookupTable = new RegionOverlapLookupTable();
		//selectedRegionRelativePositions = new long[numberOfSelectedRegions];

        featureFileReader.reset();
        System.out.println("Filling region references... ");
		int index = 0;
		long pos;
		insideReferenceSize = 0;
        GenomicFeature region;
        int regionsWithMissingChromosomesCount = 0;

        while((region = featureFileReader.readNextRecord()) != null){
            /*if (!region.getFeature().equalsIgnoreCase("exon")) {
                continue;
            }*/
            pos = locator.getAbsoluteCoordinates(region.getSequenceName(),region.getStart());
            if (pos == -1) {
                selectedRegionStarts[index] = -1;
                selectedRegionEnds[index] = -1;
                regionsWithMissingChromosomesCount++;
                continue;
            }
	        int regionLength = region.getEnd() - region.getStart() + 1;
            // TEMPTODO: delete next line
            //insideReferenceSize += regionLength;
            selectedRegionStarts[index] = pos;
            selectedRegionEnds[index] = pos + regionLength - 1;
            regionOverlapLookupTable.putRegion(region.getStart(), region.getEnd(),
                    region.getSequenceName(), region.isPositiveStrand() );

			index++;
		}

        if (regionsWithMissingChromosomesCount > 0)  {
            if (regionsWithMissingChromosomesCount == numberOfSelectedRegions) {
                throw new RuntimeException("The feature file with regions can not be associated with the BAM file.\n" +
                    "Please check, if the chromosome names match in the annotation file and the alignment file.");
            } else {
                String msg = regionsWithMissingChromosomesCount + " regions were skipped because chromosome" +
                        " name was not found in the BAM file.";
                bamStats.addWarning(WARNING_ID_CHROMOSOME_NOT_FOUND, msg);
            }

        }

        featureFileReader.close();

    }

    private int computeWindowSize(long referenceSize, int numberOfWindows){
		int windowSize = (int)Math.floor((double)referenceSize/(double)numberOfWindows);
		if(((double)referenceSize/(double)numberOfWindows)>windowSize){
			windowSize++;
		}

		return windowSize;
	}


	/*private int computeEffectiveNumberOfWindows(long referenceSize, int windowSize){
		int numberOfWindows = (int)Math.floor((double)referenceSize/(double)windowSize);
		if(((double)referenceSize/(double)windowSize)>numberOfWindows){
			numberOfWindows++;
		}

		return numberOfWindows;
	}*/

    private ArrayList<Long> computeWindowPositions(int windowSize){
        List<ContigRecord> contigs = locator.getContigs();
        ArrayList<Long> windowStarts = new ArrayList<Long>();

        long startPos = 1;
        int i = 0;
        int numContigs  = contigs.size();
        chromosomeWindowIndexes.add(0);
        while (startPos < referenceSize) {
            windowStarts.add(startPos);
            startPos += windowSize;

            while (i < numContigs) {
                long nextContigStart =  contigs.get(i).getEnd() + 1;
                if (startPos >= nextContigStart ) {
                    //System.out.println("Passing " + contigs.get(i).getName() );
                    if ( nextContigStart < referenceSize) {
                        //System.out.println("Chromosome window break: " + (windowStarts.size() + 1));
                        chromosomeWindowIndexes.add(windowStarts.size());
                        if (startPos > nextContigStart) {
                            windowStarts.add(nextContigStart);
                        }
                        //System.out.println("window start: " + nextContigStart);
                    }
                    i++;
                } else {
                    break;
                }
            }
        }

        return windowStarts;
    }

    private boolean readOverlapsRegions(SAMRecord read) {



        if (protocol == LibraryProtocol.NON_STRAND_SPECIFIC) {
            return regionOverlapLookupTable.overlaps(read.getAlignmentStart(),
                            read.getAlignmentEnd(), read.getReferenceName());
        } else {

            boolean readHasForwardStrand = !read.getReadNegativeStrandFlag();
            boolean forwardTranscriptStrandIsExpected;
            if (protocol == LibraryProtocol.STRAND_SPECIFIC_FORWARD) {
               forwardTranscriptStrandIsExpected =
                       ( ( read.getFirstOfPairFlag() || !read.getReadPairedFlag() ) && readHasForwardStrand) ||
                       (read.getSecondOfPairFlag() && !readHasForwardStrand);
            } else {
                forwardTranscriptStrandIsExpected =(read.getFirstOfPairFlag() && !readHasForwardStrand) ||
                 (read.getSecondOfPairFlag() && readHasForwardStrand) ;
            }

            RegionOverlapLookupTable.OverlapResult r = regionOverlapLookupTable.overlaps( read.getAlignmentStart(),
                    read.getAlignmentEnd(), read.getReferenceName(), forwardTranscriptStrandIsExpected);

            if (r.strandMatches()) {
                ++numberOfCorrectStrandReads;
            }

            return r.intervalOverlaps();

        }

    }



    public GenomeLocator getLocator() {
        return locator;
    }

    public BamStats getBamStats() {
        return bamStats;
    }

    public BamStats getOutsideBamStats() {
        return outsideBamStats;
    }

    HashMap<Long,BamGenomeWindow> getOpenOutsideWindows() {
        return openOutsideWindows;
    }



    ConcurrentMap<Long,BamGenomeWindow> getOpenWindows() {
        return openWindows;
    }

    public boolean isPairedData() {
        return isPairedData;
    }

    public boolean selectedRegionsAvailable() {
        return selectedRegionsAvailable;
    }

    public void setNumberOfWindows(int windowsNum) {
        numberOfWindows = windowsNum;
    }

    public void activeReporting(String outdir){
		this.outdir = outdir;
		this.activeReporting = true;
	}

    public void setSelectedRegions(String featureFile){
		this.featureFile = featureFile;
		selectedRegionsAvailable = true;
	}

    public void setComputeOutsideStats(boolean computeOutsideStats) {
        this.computeOutsideStats = computeOutsideStats;
    }

    public boolean getComputeOutsideStats() {
        return computeOutsideStats;
    }

    public void setNumberOfThreads(int threadNumber) {
        this.threadNumber = threadNumber;
    }

    public void setNumberOfReadsInBunch(int bunchSize) {
        numReadsInBunch = bunchSize;
    }

    public void setProtocol(LibraryProtocol protocol) {
        this.protocol = protocol;
    }

    public int getMinHomopolymerSize() {
        return minHomopolymerSize;
    }

    public void setMinHomopolymerSize(int size) {
        minHomopolymerSize = size;
    }

    public void setPathToCoverageReport(String pathToCoverageReport) {
        this.saveCoverage = true;
        this.pathToCoverageReport = pathToCoverageReport;
    }

    private static String boolToStr(boolean yes) {
        return yes ? "yes\n" : "no\n";
    }

    public String getQualimapCmdLine(boolean  drawChromosomeLimits) {

            CommandLineBuilder cmdBuilder = new CommandLineBuilder("qualimap " + Constants.TOOL_NAME_BAMQC);

            cmdBuilder.append(Constants.BAMQC_OPTION_BAM_FILE, bamFile);

            if (featureFile != null) {
                cmdBuilder.append(Constants.BAMQC_OPTION_GFF_FILE, featureFile);
                if (computeOutsideStats) {
                    cmdBuilder.append(Constants.BAMQC_OPTION_OUTSIDE_STATS);
                }
            }

            if (drawChromosomeLimits) {
                cmdBuilder.append(Constants.BAMQC_OPTION_PAINT_CHROMOSOMES);
            }

            cmdBuilder.append(Constants.BAMQC_OPTION_NUM_WINDOWS, numberOfWindows);
            cmdBuilder.append(Constants.BAMQC_OPTION_MIN_HOMOPOLYMER_SIZE, minHomopolymerSize);

            if (!skipDetectedDuplicates && skipMarkedDuplicates) {
                cmdBuilder.append(Constants.BAMQC_OPTION_SKIP_DUPLICATED);
            }

            if (!skipMarkedDuplicates && skipDetectedDuplicates) {
                cmdBuilder.append(Constants.BAMQC_OPTION_SKIP_DUPLICATED);
                cmdBuilder.append(Constants.BAMQC_OPTION_SKIP_DUPLICATES_MODE, "1");
            }

            if (skipMarkedDuplicates && skipDetectedDuplicates) {
                cmdBuilder.append(Constants.BAMQC_OPTION_SKIP_DUPLICATED);
                cmdBuilder.append(Constants.BAMQC_OPTION_SKIP_DUPLICATES_MODE, "2");
            }


            return cmdBuilder.getCmdLine();

    }



    public void prepareInputDescription(StatsReporter reporter, boolean drawChromosomeLimits) {

            String[] qualimapCommand =  { getQualimapCmdLine(drawChromosomeLimits) };
            StatsKeeper.Section qualCommandSection = new StatsKeeper.Section(Constants.TABLE_SECTION_QUALIMAP_CMDLINE);
            qualCommandSection.addRow(qualimapCommand);
            reporter.getInputDataKeeper().addSection(qualCommandSection);


            HashMap<String,String> alignParams = new HashMap<String, String>();
            alignParams.put("BAM file: ", bamFile);
            Date date = new Date();
            alignParams.put("Analysis date: ", date.toString() );
            alignParams.put("Number of windows: ", Integer.toString(numberOfWindows));
            alignParams.put("Size of a homopolymer: ", Integer.toString(minHomopolymerSize));


            Boolean.toString(true);
            alignParams.put("Draw chromosome limits: ", boolToStr(drawChromosomeLimits));
            if (!pgProgram.isEmpty()) {
                alignParams.put("Program: ", pgProgram);
                if (!pgCommandString.isEmpty()) {
                    alignParams.put("Command line: ", pgCommandString );
                }
            }


            String skipDuplicateAlnStatus = boolToStr(false);
            if (skipDetectedDuplicates) {
                skipDuplicateAlnStatus = "yes (only estimated)";
            } else if (skipMarkedDuplicates) {
                skipDuplicateAlnStatus = "yes (only flagged)";
            }
            if (skipMarkedDuplicates && skipDetectedDuplicates ) {
                skipDuplicateAlnStatus = "yes (both flagged and estimated)";
            }

            alignParams.put("Skip duplicate alignments: ", skipDuplicateAlnStatus);
            alignParams.put("Analyze overlapping paired-end reads:", boolToStr(collectIntersectingPairedEndReads));

            reporter.addInputDataSection("Alignment", alignParams);

            if ( selectedRegionsAvailable ) {
                HashMap<String,String> regionParams = new HashMap<String, String>();
                regionParams.put("GFF file: ", featureFile);
                regionParams.put("Outside statistics: ", boolToStr(computeOutsideStats));
                regionParams.put("Library protocol: ", protocol.toString() );
                reporter.addInputDataSection("GFF region", regionParams);
            }


        }


    public void setConfig(BamStatsAnalysisConfig cfg) {
        if (cfg.gffFile != null) {
            selectedRegionsAvailable = true;
            featureFile = cfg.gffFile;
            protocol = cfg.protocol;
        }
        numberOfWindows = cfg.numberOfWindows;
        minHomopolymerSize = cfg.minHomopolymerSize;

    }

    public void setLoggerThread(LoggerThread loggerThread) {
        this.loggerThread = loggerThread;
    }

    public void incNumberOfReasWithStartGreatThenEnd() {
        this.numberOfReadsWithStartGreatThenEnd++;
    }


    public void setSkipDuplicatedReads(boolean skipDuplications, SkipDuplicatesMode mode) {
        if (skipDuplications) {
            if (mode == SkipDuplicatesMode.ONLY_DETECTED_DUPLICATES) {
                this.skipDetectedDuplicates = true;
            } else if(mode == SkipDuplicatesMode.ONLY_MARKED_DUPLICATES) {
                this.skipMarkedDuplicates = true;
            } else {
                this.skipMarkedDuplicates = true;
                this.skipDetectedDuplicates = true;
            }
        }

    }


    public void activateIntersectingPairedEndReadsStats() {
        this.collectIntersectingPairedEndReads = true;
    }


}
