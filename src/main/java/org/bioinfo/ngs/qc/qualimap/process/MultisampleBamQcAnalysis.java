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

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;
import org.bioinfo.ngs.qc.qualimap.beans.*;
import org.bioinfo.ngs.qc.qualimap.common.AnalysisType;
import org.bioinfo.ngs.qc.qualimap.common.LoggerThread;
import org.bioinfo.ngs.qc.qualimap.gui.threads.ExportHtmlThread;
import org.bioinfo.ngs.qc.qualimap.gui.utils.StatsKeeper;
import org.bioinfo.ngs.qc.qualimap.gui.utils.StringUtilsSwing;
import org.jfree.chart.ChartColor;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Created by kokonech
 * Date: 6/5/14
 * Time: 12:45 PM
 */
public class MultisampleBamQcAnalysis extends AnalysisProcess{


    List<SampleInfo> bamQCResults;
    List<double[]> sampleData;
    LoggerThread loggerThread;
    Paint[] palette;
    Map<SampleInfo,String> rawDataDirs;
    Map<String,ArrayList<String>> sampleGroups;
    Map<String, Integer> groupIndex;
    boolean runBamQcFirst;
    BamStatsAnalysisConfig bamQcConfig;
    Map<Integer,Double> globalCoverageData;

    static final int NUM_FEATURES = 5;

    public MultisampleBamQcAnalysis(AnalysisResultManager tabProperties,
                                    String homePath,
                                    List<SampleInfo> bamQCResults) {
        super(tabProperties, homePath);
        this.bamQCResults = bamQCResults;
        this.palette = ChartColor.createDefaultPaintArray();
        this.rawDataDirs = new HashMap<SampleInfo, String>();

        this.sampleGroups = new HashMap<String, ArrayList<String>>();
        this.groupIndex = new HashMap<String, Integer>();

        sampleData = new ArrayList<double[]>();
        globalCoverageData = new HashMap<Integer, Double>();

    }

    @Override
    public void run() throws Exception {

        if (runBamQcFirst) {
            runBamQcOnSamples();
        }

        loggerThread.logLine("Running multi-sample BAM QC\n");

        loggerThread.logLine("Checking input paths");
        checkInputPaths();
        processGroups();

        StatsReporter reporter = new StatsReporter();
        reporter.setFileName( "multisampleBamQcReport" );

        prepareInputDescription(reporter);
        loggerThread.logLine("Loading sample data");
        createSummaryTable(reporter);
        loggerThread.logLine("Creating charts");
        createCharts(reporter);


        tabProperties.addReporter(reporter);

    }

    private void processGroups() {

        int numGroups = 0;
        for (SampleInfo s : bamQCResults) {
            if (sampleGroups.containsKey(s.group) ){
                ArrayList<String> samples = sampleGroups.get(s.group);
                samples.add(s.name);
            } else {
                ArrayList<String> samples = new ArrayList<String>();
                samples.add(s.name);
                sampleGroups.put(s.group, samples);
                groupIndex.put(s.group, numGroups);
                numGroups++;
            }
        }

        if (bamQCResults.size() == numGroups ) {
            sampleGroups.clear();
        }

    }


    public void setRunBamQcFirst(BamStatsAnalysisConfig cfg) {
        this.runBamQcFirst = true;
        this.bamQcConfig = cfg;
    }


    void initOutputDir(String outdir){

        if(!outdir.isEmpty()){
        	if(new File(outdir).exists()){
				loggerThread.logLine("Output directory already exists.");
			} else {
				boolean ok = new File(outdir).mkdirs();
                if (!ok) {
                    loggerThread.logLine("Failed to create output directory.");
                }
			}
		}
	}


    private void runBamQcOnSamples() throws Exception {

        loggerThread.logLine("Running BAM QC on given samples\n");

        for (SampleInfo s : bamQCResults) {

            String bamFilePath = s.path;
            loggerThread.logLine("Started processing " + bamFilePath + "\n\n");

            String sampleOutdir = FilenameUtils.removeExtension(new File(bamFilePath).getAbsolutePath()) + "_stats";
            initOutputDir(sampleOutdir);

            loggerThread.updateProgress(0);
            BamStatsAnalysis bamQC = new BamStatsAnalysis(bamFilePath);
            bamQC.setConfig(bamQcConfig);
            bamQC.setLoggerThread(loggerThread);
            bamQC.run();

            BamQCRegionReporter reporter = new BamQCRegionReporter(bamQcConfig.regionsAvailable(),false);
            reporter.setPaintChromosomeLimits(bamQcConfig.drawChromosomeLimits);
            reporter.writeReport(bamQC.getBamStats(),sampleOutdir, true);
            reporter.loadReportData(bamQC.getBamStats());
            reporter.computeChartsBuffers(bamQC.getBamStats(), bamQC.getLocator(), bamQC.isPairedData());

            AnalysisResultManager resultManager = new AnalysisResultManager(AnalysisType.BAM_QC);
            resultManager.addReporter(reporter);

            Thread exportReportThread = new ExportHtmlThread(resultManager,sampleOutdir);
            exportReportThread.run();
            loggerThread.updateProgress(100);
            loggerThread.logLine("Finished processing " + bamFilePath + "\n");
            loggerThread.logLine("BAM QC results are saved to  " + sampleOutdir + "\n");

            s.path = sampleOutdir;

        }
    }


    private BamStats loadSummaryStats(String inputFilePath) throws IOException {
        BamStats bamStats = new BamStats(null,null, 0,0);

        BufferedReader bufferedReader = new BufferedReader(new FileReader(inputFilePath));
        String line;
        while ( (line = bufferedReader.readLine()) != null) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            if (line.contains("number of mapped reads =")) {
                long numMappedReads = Long.parseLong(line.split("=")[1].split("\\(")[0].trim().replaceAll("[\\,]", ""));
                bamStats.setNumberOfMappedReads(numMappedReads);
            } else if (line.contains("mean coverageData =")) {
                double meanCoverage = Double.parseDouble( line.split("=")[1].trim().replaceAll("[X\\,]", "") );
                bamStats.setCoverageMean(meanCoverage);
            } else if (line.contains("std coverageData =")) {
                double stdCoverage = Double.parseDouble(line.split("=")[1].trim().replaceAll("[X\\,]", ""));
                bamStats.setCoverageStd(stdCoverage);
            } else if (line.contains("mean mapping quality =")) {
                double mappingQuality = Double.parseDouble(line.split("=")[1].trim().replaceAll("\\,", ""));
                bamStats.setMeanMappingQuality(mappingQuality);
            } else if (line.contains("GC percentage =")) {
                // This is actually in percents already - only should be used in the context of Multiple BAM QC
                double gcPercentage = Double.parseDouble(line.split("=")[1].trim().replace("%", ""));
                bamStats.setMeanGcContent(gcPercentage);
            } else if (line.contains("median insert size =")) {
                int insertSize = Integer.parseInt(line.split("=")[1].trim().replaceAll("\\,",""));
                bamStats.setMedianInsertSize(insertSize);
            }




        }

        return bamStats;

    }


    private void createSummaryTable(StatsReporter reporter) throws IOException {

        logLine("Creating summary...\n");

        StatsKeeper summaryKeeper = reporter.getSummaryStatsKeeper();

        StatsKeeper.Section globalsSection = new StatsKeeper.Section("Globals");
        globalsSection.addRow("Number of samples", Integer.toString(bamQCResults.size()));
        boolean  groupsAvailable =  sampleGroups.size() > 0;
        if (groupsAvailable) {
            globalsSection.addRow("Number of groups", Integer.toString(sampleGroups.size()));
        }

        StatsKeeper tableDataKeeper = reporter.getTableDataStatsKeeper();
        tableDataKeeper.setName("Sample statistics");

        StatsKeeper.Section headerSection = new StatsKeeper.Section("header");
        String[] header = {"Sample name", "Coverage mean", "Coverage std",
                "GC percentage", "Mapping quality mean", "Insert size median" };
        if (groupsAvailable) {
            header = new String[]{"Sample", "Group", "Coverage mean", "Coverage std",
                    "GC percentage", "Mapping quality mean", "Insert size median"};
        }

        headerSection.addRow( header );

        tableDataKeeper.addSection(headerSection);


        StatsKeeper.Section dataSection = new StatsKeeper.Section("data");


        long totalNumReads = 0;
        double totalMeanCoverage = 0;
        double totalGcContent = 0;
        double totalMappingQuality = 0;
        double totalInsertSize = 0;

        for (SampleInfo bamQcResult : bamQCResults) {
            String path = bamQcResult.path + File.separator + "genome_results.txt";
            BamStats stats = loadSummaryStats(path);

            String[] row = new String[header.length];
            row[0] = bamQcResult.name;
            int mv = 0;
            if (sampleGroups.size() > 0) {
                row[1] = bamQcResult.group;
                mv = 1;
            }
            row[1 + mv ] = Double.toString( stats.getMeanCoverage() );
            row[2 + mv ] = Double.toString( stats.getStdCoverage() );
            row[3 + mv ] = Double.toString( stats.getMeanGcRelativeContent() );
            row[4 + mv ] = Double.toString( stats.getMeanMappingQualityPerWindow() );
            row[5 + mv ] = Double.toString( stats.getMedianInsertSize() );
            dataSection.addRow(row);

            totalNumReads += stats.getNumberOfMappedReads();
            totalMeanCoverage += stats.getMeanCoverage();
            totalGcContent += stats.getMeanGcRelativeContent();
            totalMappingQuality += stats.getMeanMappingQualityPerWindow();
            totalInsertSize += stats.getMedianInsertSize();

            double[] sample = new double[NUM_FEATURES];
            sample[0] = stats.getMeanCoverage();
            sample[1] = stats.getStdCoverage();
            sample[2] = stats.getMeanGcRelativeContent();
            sample[3] = stats.getMeanMappingQualityPerWindow();
            sample[4] = stats.getMedianInsertSize();
            sampleData.add(sample);


        }

        StringUtilsSwing sdf = new StringUtilsSwing();


        int numSamples = sampleData.size();
        globalsSection.addRow("Total number of mapped reads", sdf.formatLong(totalNumReads) );
        globalsSection.addRow("Mean samples coverage", sdf.formatDecimal(totalMeanCoverage / numSamples ));
        globalsSection.addRow("Mean samples GC-content", sdf.formatDecimal(totalGcContent / numSamples ));
        globalsSection.addRow("Mean samples mapping quality", sdf.formatDecimal(totalMappingQuality / numSamples ));
        if (totalInsertSize > 0) {
            globalsSection.addRow("Mean samples insert size", sdf.formatDecimal(totalInsertSize / numSamples ));
        }
        summaryKeeper.addSection(globalsSection);

        tableDataKeeper.addSection(dataSection);



    }


    XYVector loadColumnData(File inputFile, double minX, double maxX, int dataColumn) throws IOException {
        XYVector data = new XYVector();

        BufferedReader bufferedReader = new BufferedReader(new FileReader(inputFile));
        String line;
        while ( (line = bufferedReader.readLine()) != null) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }

            String[] items = line.split("\t");
            if (items.length < dataColumn + 1) {
                continue;
            }

            double d1 = Double.parseDouble(items[0]);
            if (d1 < minX || d1 >= maxX) {
                continue;
            }
            double d2 = Double.parseDouble(items[dataColumn]);

            data.addItem( new XYItem(d1,d2));

        }
        return data;
    }


    Color getSampleColor(int idx) {
        return (Color) palette[idx % palette.length];
    }

    QChart createHistogramBasedChart(String chartName, String dataPath, String xTitle, String yTitle) throws IOException {
        return createHistogramBasedChart(chartName, dataPath, xTitle, yTitle, 0);
    }


    QChart createHistogramBasedChart(String chartName, String dataPath, String xTitle, String yTitle, int minXValue) throws IOException {
        BamQCChart baseChart = new BamQCChart(chartName,
                            "Multi-sample BAM QC", xTitle, yTitle);

        int i = 0;
        Set<Integer> shownInLegend = new HashSet<Integer>();

        for (SampleInfo bamQcResult : bamQCResults) {
            String path = rawDataDirs.get(bamQcResult) + File.separator + dataPath;
            File inputFile = new File(path);
            if (!inputFile.exists()) {
                continue;
            }
            XYVector histData = loadColumnData(inputFile, minXValue, Double.MAX_VALUE, 1);
            if (histData.getSize() == 0) {
                continue;
            }
            if (sampleGroups.size() == 0) {
                baseChart.addSeries(bamQcResult.name, histData, getSampleColor(i) );
            } else {
                int gIdx = groupIndex.get(bamQcResult.group);
                boolean  showInLegend = !shownInLegend.contains(gIdx);
                baseChart.addSeries(bamQcResult.group, histData, getSampleColor(gIdx), showInLegend );
                shownInLegend.add(gIdx);
            }


            ++i;
        }

        baseChart.render();

        return new QChart(chartName, baseChart.getChart());
    }

    QChart createReadsGcContentChart(String chartName, String dataPath, String xTitle, String yTitle) throws IOException {
            BamQCChart baseChart = new BamQCChart(chartName,
                                "Multi-sample BAM QC", xTitle, yTitle);

            int i = 0;
            Set<Integer> shownInLegend = new HashSet<Integer>();


            for (SampleInfo bamQcResult : bamQCResults) {

                File inputFile = new File(  rawDataDirs.get(bamQcResult) + File.separator + dataPath );

                if (!inputFile.exists()) {
                    continue;
                }

                XYVector cData = loadColumnData(inputFile, 0, Double.MAX_VALUE, 2);
                XYVector gData = loadColumnData(inputFile, 0, Double.MAX_VALUE, 3);

                if (cData.getSize() != gData.getSize()) {
                    continue;
                }

                XYVector gcData = new XYVector();
                for (int j = 0; j < cData.getSize(); ++j) {
                    double pos = cData.get(j).getX();
                    double gc = cData.get(j).getY() + gData.get(j).getY();
                    gcData.addItem( new XYItem(pos,gc));
                }

                if (sampleGroups.size() == 0) {
                    baseChart.addSeries(bamQcResult.name, gcData, getSampleColor(i) );
                } else {
                    int gIdx = groupIndex.get(bamQcResult.group);
                    boolean  showInLegend = !shownInLegend.contains(gIdx);
                    baseChart.addSeries(bamQcResult.group, gcData, getSampleColor(gIdx), showInLegend );
                    shownInLegend.add(gIdx);
                }
                ++i;
            }

            baseChart.render();

            return new QChart(chartName, baseChart.getChart());
        }



    XYVector scaleXAxis(XYVector raw) {
        XYVector scaled = new XYVector();


        double scaleFactor = 1./raw.getSize();

        for (int i = 0; i < raw.getSize(); ++i) {
            XYItem item = raw.get(i);
            XYItem newItem = new XYItem(scaleFactor*i, item.getY());
            scaled.addItem(newItem);
        }

        return scaled;

    }



    QChart createCoverageAcrossReferenceChart() throws IOException {
        BamQCChart baseChart = new BamQCChart("Coverage Across Reference",
                            "Multi-sample BAM QC", "Position in reference (relative)", "Coverage");

        DescriptiveStatistics stats = new DescriptiveStatistics();
        int k = 0;
        Set<Integer> shownInLegend = new HashSet<Integer>();
        for (SampleInfo bamQcResult : bamQCResults) {
            String path = rawDataDirs.get(bamQcResult) + File.separator + "coverage_across_reference.txt";
            XYVector rawData = loadColumnData(new File(path), 0, Double.MAX_VALUE, 1);
            XYVector scaledData = scaleXAxis(rawData);
            for (int i = 0; i < scaledData.getSize(); ++i) {
                stats.addValue( scaledData.get(i).getY() );
            }
            if (sampleGroups.size() == 0) {
                baseChart.addSeries(bamQcResult.name, scaledData, getSampleColor(k) );
            } else {
                int gIdx = groupIndex.get(bamQcResult.group);
                boolean  showInLegend = !shownInLegend.contains(gIdx);
                baseChart.addSeries(bamQcResult.group, scaledData, getSampleColor(gIdx), showInLegend );
                shownInLegend.add(gIdx);
            }

            ++k;
        }
        baseChart.setDomainAxisIntegerTicks(false);

        baseChart.render();
        double p75 = stats.getPercentile(75);
        if (p75 > 0) {
            baseChart.getChart().getXYPlot().getRangeAxis().setRange(0, 2*p75);
        }
        stats.clear();

        return new QChart("Coverage across reference", baseChart.getChart());
    }

    QChart createAcrossReferenceChart(String chartName, String dataPath, String yTitle) throws IOException {
        BamQCChart baseChart = new BamQCChart(chartName,
                            "Multi-sample BAM QC", "Position in reference (relative)", yTitle);

        int k = 0;
        Set<Integer> shownInLegend = new HashSet<Integer>();
        for (SampleInfo bamQcResult : bamQCResults) {
            File dataFile = new File( rawDataDirs.get(bamQcResult) + File.separator +  dataPath );
            if (!dataFile.exists()) {
                continue;
            }
            XYVector rawData = loadColumnData(dataFile, 0, Double.MAX_VALUE, 1);
            XYVector scaledData = scaleXAxis(rawData);

            if (sampleGroups.size() == 0) {
                baseChart.addSeries(bamQcResult.name, scaledData, getSampleColor(k) );
            } else {
                int gIdx = groupIndex.get(bamQcResult.group);
                boolean  showInLegend = !shownInLegend.contains(gIdx);
                baseChart.addSeries(bamQcResult.group, scaledData, getSampleColor(gIdx), showInLegend );
                shownInLegend.add(gIdx);
            }

            ++k;
        }
        baseChart.setDomainAxisIntegerTicks(false);

        baseChart.render();


        return new QChart(chartName, baseChart.getChart());
    }




    QChart createCoverageProfileChart(String chartName, String dataPath, String xTitle, String yTitle) throws IOException {
        BamQCChart baseChart = new BamQCChart(chartName,
                            "Multi-sample BAM QC", xTitle, yTitle);

        int i = 0;
        int maxCoverage = 50;
        Set<Integer> shownInLegend = new HashSet<Integer>();

        for (SampleInfo bamQcResult : bamQCResults) {
            String path = rawDataDirs.get(bamQcResult) + File.separator + dataPath;
            XYVector histData = loadColumnData(new File(path), 0, 1000000,1);
            integrateHistogramData(globalCoverageData, histData);
            int coverage = (int) histData.getXVector()[histData.getSize() - 1];
            maxCoverage = coverage > maxCoverage ? coverage : maxCoverage;
            if (sampleGroups.size() == 0) {
                baseChart.addSeries(bamQcResult.name, histData, getSampleColor(i) );
            } else {
                int gIdx = groupIndex.get(bamQcResult.group);
                boolean  showInLegend = !shownInLegend.contains(gIdx);
                baseChart.addSeries(bamQcResult.group, histData, getSampleColor(gIdx), showInLegend );
                shownInLegend.add(gIdx);
            }


            ++i;
        }

        chartName += " (0 - " + maxCoverage + "X)";
        baseChart.setTitle(chartName);

        baseChart.render();

        return new QChart(chartName, baseChart.getChart());
    }

    QChart createGlobalCoverageChart(String chartName, String xTitle, String yTitle) {

        BamQCChart baseChart = new BamQCChart(chartName,
                            "Multi-sample BAM QC", xTitle, yTitle);


        List<Integer> keyList = new ArrayList<Integer>(globalCoverageData.keySet());
        Collections.sort(keyList);

        XYVector histData = new XYVector();

        for (int cov : keyList) {
            histData.addItem( new XYItem(cov, globalCoverageData.get(cov)));
        }

        baseChart.addSeries("All samples combined", histData, getSampleColor(0), true );
        baseChart.render();

        return new QChart(chartName, baseChart.getChart());
    }


    private void integrateHistogramData(Map<Integer, Double> globalCoverageData, XYVector histData) {
        int size = histData.getSize();
        for (int i = 0; i < size; i++) {
            XYItem item = histData.get(i);
            int x =  (int) item.getX();
            double data = globalCoverageData.containsKey(x) ? globalCoverageData.get(x) : 0;
            globalCoverageData.put(x, data + item.getY());
        }
    }

    private QChart createPCABiPlot() {

        PrincipleComponentAnalysis pca = new PrincipleComponentAnalysis();
        pca.setup(bamQCResults.size(), NUM_FEATURES);

        for (double[] sample : sampleData) {
            pca.addSample( sample );
        }

        logLine("Running PCA...\n");
        pca.computeBasis(2);

        String chartName =  "PCA";

        BamQCPointChart baseChart = new BamQCPointChart(chartName,
                                    "Multi-sample BAM QC", "PC1", "PC2");

        for (int i = 0; i < sampleData.size(); ++i) {
            double[] transformedSample = pca.sampleToEigenSpace(sampleData.get(i));
            if (sampleGroups.size() == 0) {
                baseChart.addPoint(bamQCResults.get(i).name, transformedSample[0],transformedSample[1], getSampleColor(i) );
            } else {
                String groupName = bamQCResults.get(i).group;
                int gIdx = groupIndex.get(groupName);
                baseChart.addPointToGroupSeries(groupName, transformedSample[0], transformedSample[1], getSampleColor(gIdx));
            }
        }

        if (sampleGroups.size() > 0) {
            baseChart.finalizeGroupSeries();
        }

        baseChart.render();

        return new QChart(chartName, baseChart.getChart());
    }

    private void createCharts(StatsReporter reporter) throws Exception {

        ArrayList<QChart> charts = new ArrayList<QChart>();

        QChart pcaBiPlot = createPCABiPlot();
        charts.add(pcaBiPlot);

        logLine("Creating charts...\n");

        QChart coverageAcrossRefChart = createCoverageAcrossReferenceChart();
        charts.add(coverageAcrossRefChart);

        QChart coverageChart = createCoverageProfileChart("Coverage Histogram", "coverage_histogram.txt",
                "Coverage (X)", "Number of genomic locations");
        charts.add(coverageChart);

        QChart globalCoverageChart = createGlobalCoverageChart("Global Coverage Histogram",
                "Coverage (X)", "Number of genomic locations");
        charts.add(globalCoverageChart);

        QChart genomeFractionCoverage = createHistogramBasedChart("Genome Fraction Coverage",
                "genome_fraction_coverage.txt", "Coverage (X)", "Fraction of genome (%)");
        charts.add(genomeFractionCoverage);

        QChart duplicationRate = createHistogramBasedChart("Duplication Rate Histogram",
                "duplication_rate_histogram.txt", "Duplication rate", "Number of genomic locations");
        charts.add(duplicationRate);


        QChart readsGcContent = createReadsGcContentChart("Mapped reads GC-content",
                "mapped_reads_nucleotide_content.txt", "Read position (bp)", "GC-content (%)" );
        charts.add(readsGcContent);

        QChart readsClippingProfile = createHistogramBasedChart("Mapped Reads Clipping Profile",
                "mapped_reads_clipping_profile.txt", "Read position (bp)", "Clipped bases (%)");
        charts.add(readsClippingProfile);


        QChart readsGCContentDistr = createHistogramBasedChart("Mapped Reads GC-content Distribution",
                        "mapped_reads_gc-content_distribution.txt", "GC Content (%)", "Fraction of reads");
        charts.add(readsGCContentDistr);

        QChart mappingQualityAcrossRef = createAcrossReferenceChart("Mapping Quality Across Reference",
                         "mapping_quality_across_reference.txt", "Mapping Quality");
        charts.add(mappingQualityAcrossRef);

        QChart mappingQualityHist = createHistogramBasedChart("Mapping Quality Histogram",
                               "mapping_quality_histogram.txt", "Mapping quality", "Number of genomic locations");
        charts.add(mappingQualityHist);

        QChart insertSizeAcrossRef = createAcrossReferenceChart("Insert Size Across Reference",
                                 "insert_size_across_reference.txt", "Insert Size");
        charts.add(insertSizeAcrossRef);

        QChart insertSizeHist = createHistogramBasedChart("Insert Size Histogram",
                        "insert_size_histogram.txt", "Insert Size", "Number of reads", 1);
        charts.add(insertSizeHist);

        reporter.setChartList(charts);

    }


    private void prepareInputDescription(StatsReporter reporter) {
        HashMap<String,String> sampleParams = new HashMap<String, String>();
        for ( SampleInfo info : bamQCResults ) {
            if (sampleGroups.size() == 0) {
                sampleParams.put(info.name, info.path );
            } else {
                sampleParams.put(info.name + " (" + info.group + ")", info.path);
            }
        }
        String sectionName = sampleGroups.size() == 0 ? "Samples" : "Samples (with groups)";
        reporter.addInputDataSection(sectionName, sampleParams);


    }


    private String findRawDataPath(String targetDir) {

        File dir = new File(targetDir);

        if (!dir.exists() || !dir.isDirectory())  {
            return null;
        }

        File[] children = dir.listFiles();
        Arrays.sort(children);

        for (File child : children ) {
            String fileName = child.getName();
            if (child.isDirectory() && fileName.startsWith("raw_data")) {
                return child.getAbsolutePath();
            }
        }

        return null;

    }


    private void checkInputPaths() throws RuntimeException {
        for (SampleInfo sampleInfo : bamQCResults) {

            if (! new File(sampleInfo.path).isDirectory()) {
                sampleInfo.path = (new File(sampleInfo.path).getParent() );
            }
            String rawDataDir = findRawDataPath(sampleInfo.path);


            if (rawDataDir == null) {
                throw new RuntimeException("The raw data doesn't exist for sample: " + sampleInfo.name +
                        "\nFolder path:" + sampleInfo.path +
                        "\nPlease check raw data directory is present.\n");
            }

            rawDataDirs.put(sampleInfo, rawDataDir);

        }
    }

    public void setOutputParsingThread(LoggerThread loggerThread) {
        this.loggerThread = loggerThread;
    }

    public static List<SampleInfo> parseInputFile(String inputFilePath) throws IOException {
        ArrayList<SampleInfo> paths = new ArrayList<SampleInfo>();

        BufferedReader bufferedReader = new BufferedReader(new FileReader(inputFilePath));
        String line;
        int numSamplesWithGroup = 0;

        while ( (line = bufferedReader.readLine()) != null) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }

            String[] items = line.trim().split("\\s+");

            boolean okLength = items.length == 2 || items.length == 3;
            if (!okLength) {
                continue;
            }

            String path = items[1];
            File sampleFile = new File(path);
            if (!sampleFile.exists()) {
                // Try relative path
                path = new File(inputFilePath).getParent() + File.separator + path;
            }
            if (items.length == 2) {
                paths.add(  new SampleInfo(items[0], path ) );
            } else {
                numSamplesWithGroup++;
                paths.add( new SampleInfo(items[0], path, items[2]) );
            }

        }

        if (numSamplesWithGroup > 0 && numSamplesWithGroup != paths.size()) {
            throw new IOException("Error parsing configuration: number of defined groups is less than number of samples!");
        }


        return paths;

    }

}
