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
package org.bioinfo.ngs.qc.qualimap.gui.panels;

import net.miginfocom.swing.MigLayout;
import org.apache.commons.io.FilenameUtils;
import org.bioinfo.commons.io.utils.FileUtils;
import org.bioinfo.ngs.qc.qualimap.common.AnalysisType;
import org.bioinfo.ngs.qc.qualimap.common.Constants;
import org.bioinfo.ngs.qc.qualimap.common.LibraryProtocol;
import org.bioinfo.ngs.qc.qualimap.common.SkipDuplicatesMode;
import org.bioinfo.ngs.qc.qualimap.gui.dialogs.AnalysisDialog;
import org.bioinfo.ngs.qc.qualimap.gui.frames.HomeFrame;
import org.bioinfo.ngs.qc.qualimap.gui.threads.BamAnalysisThread;
import org.bioinfo.ngs.qc.qualimap.gui.utils.*;
import org.bioinfo.ngs.qc.qualimap.process.BamStatsAnalysis;

import javax.activation.MimetypesFileTypeMap;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.*;


/**
 * Created by kokonech
 * Date: 12/8/11
 * Time: 11:18 AM
 */
public class BamAnalysisDialog extends AnalysisDialog implements ActionListener {

    JButton pathDataFileButton, pathGffFileButton;
    JTextField pathDataFile, pathGffFile, valueNw;
    JSpinner numThreadsSpinner,numReadsPerBunchSpinner, minHmSizeSpinner;
    JCheckBox drawChromosomeLimits, skipDuplicates, computeOutsideStats, advancedInfoCheckBox, analyzeRegionsCheckBox;
    JCheckBox compareGcContentDistr, detectOverlapingPairs;
    JComboBox<String> genomeGcContentCombo, protocolCombo, selectSkipDupMethodBox;
    JLabel labelPathDataFile, labelPathAditionalDataFile, labelNw,
            labelNumThreads, labelNumReadsPerBunch, protocolLabel, minHmSizeLabel;
    File inputFile, regionFile;

    StringBuilder stringValidation;

    final String startButtonText = ">>> Start analysis";

    public BamAnalysisDialog(HomeFrame homeFrame) {

        super(homeFrame, "Analyze alignment" );

        getContentPane().setLayout(new MigLayout("insets 20"));

        labelPathDataFile = new JLabel();
        labelPathDataFile.setText("BAM/SAM file:");
        add(labelPathDataFile, "");

        pathDataFile = new JTextField(40);
        pathDataFile.setToolTipText("Path to the alignment file. Note: BAM file has to be sorted by coordinate.");
        add(pathDataFile, "grow");

        pathDataFileButton = new JButton();
		pathDataFileButton.setAction(getActionLoadBamFile());
        pathDataFileButton.setText("...");
		add(pathDataFileButton, "align center, wrap");

        analyzeRegionsCheckBox = new JCheckBox("Analyze regions");
        analyzeRegionsCheckBox.addActionListener(this);
        analyzeRegionsCheckBox.setToolTipText("Check to only analyze the regions defined in the features file");
        add(analyzeRegionsCheckBox, "wrap");

        labelPathAditionalDataFile = new JLabel("Regions file (GFF/BED):");
        add(labelPathAditionalDataFile, "");

        pathGffFile = new JTextField(40);
        pathGffFile.setToolTipText("Path to GFF/GTF or BED file containing regions of interest");
        add(pathGffFile, "grow");

        pathGffFileButton = new JButton();
        pathGffFileButton.setAction(getActionLoadAdditionalFile());
        pathGffFileButton.setText("...");
        add(pathGffFileButton, "align center, wrap");

        protocolLabel = new JLabel("Library strand specificity:");
        add(protocolLabel);
        String[] names = LibraryProtocol.getProtocolNames();
        protocolCombo = new JComboBox<String>( names );
        add(protocolCombo, "wrap");

        computeOutsideStats = new JCheckBox("Analyze outside regions");
        computeOutsideStats.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        computeOutsideStats.setToolTipText("<html>Check to perform a separate analysis for the genome " +
                "<br>regions complement to those in the features file</html>");
        add(computeOutsideStats, "wrap");


        drawChromosomeLimits = new JCheckBox("Chromosome limits");
        drawChromosomeLimits.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        drawChromosomeLimits.setToolTipText("Check to draw the chromosome limits");
        drawChromosomeLimits.setSelected(true);
        add(drawChromosomeLimits, "");

        detectOverlapingPairs = new JCheckBox("Detect overlapping paired-end reads");
        detectOverlapingPairs.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detectOverlapingPairs.setToolTipText("Activate this option to detect overlapping paired-end read" +
             " alignments and compute adapted mean coverage.");
        detectOverlapingPairs.setSelected(false);

        add(detectOverlapingPairs, "wrap");

        skipDuplicates = new JCheckBox("Skip duplicates");
        skipDuplicates.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        skipDuplicates.setToolTipText("Activate this option to skip duplicated alignments from analysis");
        skipDuplicates.addActionListener(this);
        skipDuplicates.setSelected(false);
        add(skipDuplicates, "wrap");

        String[] skipDupStyles =  {  "Only flagged", "Only estimated", "Both flagged and estimated"};
        selectSkipDupMethodBox = new JComboBox<String>( skipDupStyles );
        selectSkipDupMethodBox.setToolTipText("Select which type of duplicate alignments should be skipped.");
        add(selectSkipDupMethodBox, "gapleft 20, span 2, wrap");

        compareGcContentDistr = new JCheckBox("Compare GC content distribution with:");
        compareGcContentDistr.addActionListener(this);
        compareGcContentDistr.setToolTipText("Compare sample GC distribution with the corresponding genome");
        add(compareGcContentDistr, "span 2, wrap");

        Map<String,String> gcFileMap = BamStatsAnalysis.getGcContentFileMap();
        String[] genomes = new String[gcFileMap.size()];
        gcFileMap.keySet().toArray(genomes);
        // small trick for human genome to be first
        Arrays.sort(genomes);
        genomeGcContentCombo = new JComboBox<String>( genomes );
        add( genomeGcContentCombo, "gapleft 20, span 2, wrap" );

        // Input Line of information (check to show the advance info)
        advancedInfoCheckBox = new JCheckBox("Advanced options");
        advancedInfoCheckBox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add(advancedInfoCheckBox,"wrap");

        labelNw = new JLabel("Number of windows:");
		add(labelNw, "gapleft 20");

        valueNw = new JTextField(10);
		valueNw.setDocument(new JTextFieldLimit(6, true));
        valueNw.setText("" + Constants.DEFAULT_NUMBER_OF_WINDOWS);
        valueNw.setToolTipText("Number of sampling windows across the genome");
        add(valueNw, "wrap");

        minHmSizeLabel = new JLabel("Homopolymer size:");
        add(minHmSizeLabel, "gapleft 20");
        minHmSizeSpinner = new JSpinner(new SpinnerNumberModel(Constants.DEFAULT_HOMOPOLYMER_SIZE, 2, 7, 1));
        minHmSizeSpinner.setToolTipText("<html>Only homopolymers of this size or larger will be considered " +
                "<br>when estimating number of homopolymer indels.</html>" );
        add(minHmSizeSpinner, "wrap");

        labelNumThreads = new JLabel("Number of threads:");
        add(labelNumThreads, "gapleft 20");
        int numCPUs =  Runtime.getRuntime().availableProcessors();
        numThreadsSpinner = new JSpinner(new SpinnerNumberModel(numCPUs, 1, numCPUs*2, 1));
        numThreadsSpinner.setToolTipText("Number of computational threads");
        add(numThreadsSpinner, "wrap");

        labelNumReadsPerBunch = new JLabel("Size of the chunk:");
        add(labelNumReadsPerBunch, "gapleft 20");
        numReadsPerBunchSpinner = new JSpinner(new SpinnerNumberModel(Constants.DEFAULT_CHUNK_SIZE, 100, 5000, 1));
        numReadsPerBunchSpinner.setToolTipText("<html>To speed up the computation reads are analyzed in chunks. " +
                "Each bunch is analyzed by single thread. <br>This option controls the number of reads in the chunk." +
                "<br>Smaller number may result in lower performance, " +
                "but also the memory consumption will be reduced.</html>");
        add(numReadsPerBunchSpinner, "wrap 20px");

        advancedInfoCheckBox.addActionListener(this);

        // Action done while the statistics graphics are loaded
        setupProgressStream();
        add(progressStream, "align center");

        setupProgressBar();
        add(progressBar, "grow, wrap 30px");

        startAnalysisButton = new JButton();
        startAnalysisButton.setAction(getActionLoadQualimap());
        startAnalysisButton.setText(startButtonText);

        add(new JLabel(""), "span 2");
        add(startAnalysisButton, "wrap");


        if (System.getenv("QUALIMAP_DEBUG") != null) {
            pathDataFile.setText("/data/qualimap_release_data/counts/kidney.sorted.with_duplicates.bam");
        }


        updateState();
        pack();

        setResizable(false);

    }

    public void updateState() {

        boolean analyzeRegions = analyzeRegionsCheckBox.isSelected();
        labelPathAditionalDataFile.setEnabled(analyzeRegions);
        pathGffFile.setEnabled(analyzeRegions);
        pathGffFileButton.setEnabled(analyzeRegions);
        computeOutsideStats.setEnabled(analyzeRegions);
        protocolCombo.setEnabled(analyzeRegions);
        protocolLabel.setEnabled(analyzeRegions);

        boolean advOptionsEnabled = advancedInfoCheckBox.isSelected();
        valueNw.setEnabled(advOptionsEnabled);
        labelNw.setEnabled(advOptionsEnabled);
        labelNumThreads.setEnabled(advOptionsEnabled);
        numThreadsSpinner.setEnabled(advOptionsEnabled);
        numReadsPerBunchSpinner.setEnabled(advOptionsEnabled);
        labelNumReadsPerBunch.setEnabled(advOptionsEnabled);
        minHmSizeLabel.setEnabled(advOptionsEnabled);
        minHmSizeSpinner.setEnabled(advOptionsEnabled);

        genomeGcContentCombo.setEnabled(compareGcContentDistr.isSelected());
        selectSkipDupMethodBox.setEnabled(skipDuplicates.isSelected());
    }


    /**
	 * Action to load the input data file.
	 *
	 * @return AbstractAction with the event
	 */
	private AbstractAction getActionLoadBamFile() {

		 return new AbstractAction() {
			private static final long serialVersionUID = -8111339366112980049L;

            boolean hasExtension(final String fileName, final String ext) {
                return (fileName.substring(fileName.lastIndexOf(".") + 1).equalsIgnoreCase(ext));
            }

			public void actionPerformed(ActionEvent evt) {

                JFileChooser fileChooser = HomeFrame.getFileChooser();

                FileFilter filter = new FileFilter() {
					public boolean accept(File fileShown) {
						boolean result = true;

						if (!fileShown.isDirectory() &&!hasExtension(fileShown.getName(), Constants.FILE_EXTENSION_BAM)
                                && !hasExtension(fileShown.getName(), Constants.FILE_EXTENSION_SAM) ) {
							result = false;
						}

						return result;
					}

					public String getDescription() {
						return ("BAM/SAM Files (*.bam, *.sam)");
					}
				};
				fileChooser.setFileFilter(filter);

				int valor = fileChooser.showOpenDialog(homeFrame.getCurrentInstance());

				if (valor == JFileChooser.APPROVE_OPTION) {
					pathDataFile.setText(fileChooser.getSelectedFile().getPath());
				}
			}
		 };

	}

    /**
	 * Action to calculate the qualimap with the input data.
	 *
	 * @return AbstractAction with the event
	 */
	private AbstractAction getActionLoadQualimap() {

        return new AbstractAction() {
			private static final long serialVersionUID = 8329832238125153187L;

			public void actionPerformed(ActionEvent evt) {
                // We can load from file or from a BAM file
                TabPageController tabPageController = new TabPageController(AnalysisType.BAM_QC);
				if (validateInput()) {
					// If the input has the required values, load the
					// results
					runAnalysis(tabPageController);
				} else {
					JOptionPane.showMessageDialog(null, stringValidation.toString(), "Error", 0);
				}
			}
		};

	}


    /**
	 * Action to load the additional input data file.
	 *
	 * @return AbstractAction with the event
	 */
	private AbstractAction getActionLoadAdditionalFile() {
		return new AbstractAction() {
			private static final long serialVersionUID = -1601146976209876607L;

			public void actionPerformed(ActionEvent evt) {

                JFileChooser fileChooser = HomeFrame.getFileChooser();

				FileFilter filter = new FileFilter() {
					public boolean accept(File fileShown) {
						boolean result = true;

                        String ext = FilenameUtils.getExtension(fileShown.getName());

						if (!fileShown.isDirectory() && !Constants.FILE_EXTENSION_REGION.containsKey(ext.toUpperCase())) {
							result = false;
						}

						return result;
					}

					public String getDescription() {
						return ("Region Files (*.gff *.gtf *.bed)");
					}
				};
				fileChooser.setFileFilter(filter);
				int valor = fileChooser.showOpenDialog(homeFrame.getCurrentInstance());

				if (valor == JFileChooser.APPROVE_OPTION) {
					pathGffFile.setText(fileChooser.getSelectedFile().getPath());
				}
			}
		};

    }

    /**
	 * Test if the input data correct.
	 *
	 * @return boolean, true if the input data are correct.
	 */
	private boolean validateInput() {
		boolean validate = true;

		stringValidation = new StringBuilder();

		// Validation for the input data file
		if ( !pathDataFile.getText().isEmpty() ) {
			inputFile = new File(pathDataFile.getText());
			String mimeType = new MimetypesFileTypeMap().getContentType(inputFile);
			if (mimeType == null ) {
				stringValidation.append(" • Incorrect MimeType for the input BAM/SAM file \n");
			}
		}

        if (inputFile == null) {
            stringValidation.append(" • The path to the SAM file is required \n");
        }else {
			try {
				FileUtils.checkFile(inputFile);
			} catch (IOException e) {
				stringValidation.append(" • ").append( e.getMessage()).append(" \n");
			}
		}

		// Validation for the regions file
		if (analyzeRegionsCheckBox.isSelected()) {
			if ( !pathGffFile.getText().isEmpty() ) {
                regionFile = new File(pathGffFile.getText());
				String mimeType = new MimetypesFileTypeMap().getContentType(regionFile);
				if ( mimeType == null ) {
					stringValidation.append(" • Incorrect MimeType for the regions data file \n");
				}
			}
			if (regionFile == null) {
				stringValidation.append(" • The path to the regions file is required \n");
			} else {
				try {
					FileUtils.checkFile(regionFile);
				} catch (IOException e) {
					stringValidation.append(" • ").append(e.getMessage()).append("\n");
				}
			}
		}

		// If we have got any error, we reset the invalidate flag
		if (stringValidation.length() > 0) {
			validate = false;
		}

		return validate;
	}

    /*
	 * Function that execute the quality map program an show the results from
	 * the input data files
	 */
	private synchronized void runAnalysis(TabPageController tabPageController) {
		BamAnalysisThread t;
        t = new BamAnalysisThread("BamAnalysisThread", this, tabPageController);

        Thread.UncaughtExceptionHandler eh = new AnalysisDialog.ExceptionHandler(this);
        t.setUncaughtExceptionHandler(eh);

		t.start();

	}

    public int getNumberOfWindows() {
        try {
         return Integer.parseInt(valueNw.getText());
        } catch (NumberFormatException ex) {
            return Constants.DEFAULT_NUMBER_OF_WINDOWS;
        }
    }

    public File getInputFile() {
        return inputFile;
    }

    public File getRegionFile() {
        return regionFile;
    }

    public boolean getDrawChromosomeLimits() {
        return drawChromosomeLimits.isSelected();
    }

    public boolean getSkipDuplicatesStatus() {
        return skipDuplicates.isSelected();
    }

    public boolean getOverlappingReadPairAlignmentStatus() {
        return detectOverlapingPairs.isSelected();
    }

    public boolean getComputeOutsideRegions() {
        return analyzeRegionsCheckBox.isSelected() && computeOutsideStats.isSelected();
    }

    public int getNumThreads() {
        return ((SpinnerNumberModel)numThreadsSpinner.getModel()).getNumber().intValue();
    }

    public void addNewPane(TabPageController tabProperties) {
        homeFrame.addNewPane(inputFile.getName(), tabProperties);
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        updateState();
    }

    public boolean compareGcContentToPrecalculated() {
        return compareGcContentDistr.isSelected();
    }

    public String getGenomeName() {
        return genomeGcContentCombo.getSelectedItem().toString();
    }

    public int getBunchSize() {
        return ((SpinnerNumberModel)numReadsPerBunchSpinner.getModel()).getNumber().intValue();
    }

    public LibraryProtocol getLibraryProtocol() {
        return LibraryProtocol.getProtocolByName(protocolCombo.getSelectedItem().toString());
    }

    public int getMinHomopolymerSize() {
        return ((SpinnerNumberModel) minHmSizeSpinner.getModel()).getNumber().intValue();
    }

    public SkipDuplicatesMode getSkipDuplicatesMode() {
        int idx = selectSkipDupMethodBox.getSelectedIndex();
        if (idx == 1) {
            return SkipDuplicatesMode.ONLY_DETECTED_DUPLICATES;
        } else if (idx == 2) {
            return SkipDuplicatesMode.BOTH;
        } else {
            return SkipDuplicatesMode.ONLY_MARKED_DUPLICATES;
        }
    }
}
