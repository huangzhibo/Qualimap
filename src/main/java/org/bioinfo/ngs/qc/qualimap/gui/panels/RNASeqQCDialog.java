/**
 * QualiMap: evaluation of next generation sequencing alignment data
 * Copyright (C) 2014 Garcia-Alcalde et al.
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
import org.bioinfo.ngs.qc.qualimap.common.*;
import org.bioinfo.ngs.qc.qualimap.gui.dialogs.AnalysisDialog;
import org.bioinfo.ngs.qc.qualimap.gui.dialogs.BrowseButtonActionListener;
import org.bioinfo.ngs.qc.qualimap.gui.frames.HomeFrame;
import org.bioinfo.ngs.qc.qualimap.gui.threads.RNASeqQCAnalysisThread;
import org.bioinfo.ngs.qc.qualimap.gui.utils.AnalysisType;
import org.bioinfo.ngs.qc.qualimap.gui.utils.TabPageController;
import org.bioinfo.ngs.qc.qualimap.process.ComputeCountsTask;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.*;

/**
 * Created by kokonech
 * Date: 7/13/13
 * Time: 11:19 AM
 */
public class RNASeqQCDialog extends AnalysisDialog implements ActionListener {

    JTextField bamPathEdit, gffPathEdit;
    JTextArea logArea;
    JButton browseBamButton, browseGffButton, startAnalysisButton;
    JComboBox strandTypeCombo, countingAlgoCombo;
    JCheckBox advancedOptions;
    JLabel countingMethodLabel;


    static class BrowseGffButtonListener extends BrowseButtonActionListener {

        RNASeqQCDialog dlg;
        Set<String> featuresTypes, featureNames;
        static final int NUM_LINES = 100000;
        static final String[] extentions = { "gtf" };

        public BrowseGffButtonListener(RNASeqQCDialog parent, JTextField textField) {
            super(parent, textField, "Annotation files", extentions );
            this.dlg = parent;
            featuresTypes = new HashSet<String>();
            featureNames = new HashSet<String>();
        }

        @Override
        public void validateInput() {

            String filePath = pathEdit.getText();
            try {
                preloadGff(filePath);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(parent,
                                    "File is not a valid GTF.",
                                    dlg.getTitle(), JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (featuresTypes.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                        "No features are found in GTF file, please make sure the file is not empty.",
                        dlg.getTitle(), JOptionPane.ERROR_MESSAGE);
            }

        }

        void preloadGff(String filePath) throws Exception {
            GenomicFeatureStreamReader parser = new GenomicFeatureStreamReader(filePath, FeatureFileFormat.GTF);
            for (int i = 0; i < NUM_LINES; ++i) {
                GenomicFeature rec = parser.readNextRecord();
                if (rec == null) {
                    break;
                }
                String featureType = rec.getFeatureName();
                featuresTypes.add(featureType);
                Collection<String> fNames = rec.getAttributeNames();
                for (String name : fNames) {
                    featureNames.add(name);
                }
            }

        }
    }

    public RNASeqQCDialog(HomeFrame homeFrame) {

        super(homeFrame, "RNASeq QC");

        getContentPane().setLayout(new MigLayout("insets 20"));

        add(new JLabel("BAM file:"), "");

        bamPathEdit = new JTextField(40);
        bamPathEdit.setToolTipText("Path to BAM alignment file");
        add(bamPathEdit, "grow");

        browseBamButton = new JButton("...");
		browseBamButton.addActionListener( new BrowseButtonActionListener(this,
                bamPathEdit, "BAM files", "bam"));
        add(browseBamButton, "align center, wrap");

        add(new JLabel("Annotation file:"), "");

        gffPathEdit = new JTextField(40);
        gffPathEdit.setToolTipText("File with the definition of the regions for the features in Ensembl GTF format).");
        add(gffPathEdit, "grow");

        browseGffButton = new JButton();
		browseGffButton.setText("...");
		browseGffButton.addActionListener(new BrowseGffButtonListener(this, gffPathEdit));
        browseBamButton.addActionListener(this);
        add(browseGffButton, "align center, wrap");


        add(new JLabel("Protocol:"));
        String[] protocolComboItems = LibraryProtocol.getProtocolNames();
        strandTypeCombo = new JComboBox<String>(protocolComboItems);
        strandTypeCombo.setToolTipText("Select the corresponding sequencing protocol");
        strandTypeCombo.addActionListener(this);
        add(strandTypeCombo, "wrap");

        advancedOptions = new JCheckBox("Advanced options:");
        advancedOptions.addActionListener(this);
        add(advancedOptions, "wrap");

        countingMethodLabel = new JLabel("Multi-mapped reads:");
        countingMethodLabel.setToolTipText("Select method to count reads that map to several genome locations");
        add(countingMethodLabel);
        String[] algoComboItems = {ComputeCountsTask.COUNTING_ALGORITHM_ONLY_UNIQUELY_MAPPED,
                ComputeCountsTask.COUNTING_ALGORITHM_PROPORTIONAL
                };
        countingAlgoCombo = new JComboBox<String>(algoComboItems);
        countingAlgoCombo.addActionListener(this);
        add(countingAlgoCombo, "wrap");


        /*add(new JLabel("Output:"), "");
        outputPathField = new JTextField(40);
        outputPathField.setToolTipText("Path to the file which will contain output");
        bamPathEdit.addCaretListener( new CaretListener() {
            @Override
            public void caretUpdate(CaretEvent caretEvent) {
                if (!bamPathEdit.getText().isEmpty()) {
                    outputPathField.setText(bamPathEdit.getText() + ".counts");
                }
            }
        });
        add(outputPathField, "grow");

        JButton browseOutputPathButton = new JButton("...");
        browseOutputPathButton.addActionListener(new BrowseButtonActionListener(this,
                outputPathField, "Counts file") );
        add(browseOutputPathButton, "wrap");*/

        add(new JLabel("Log:"), "wrap");
        logArea = new JTextArea(5,40);
        logArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane();
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setViewportView(logArea);
        add(scrollPane, "span, grow, wrap 30px");

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new MigLayout("insets 20"));

        startAnalysisButton = new JButton();
        startAnalysisButton.setActionCommand(Constants.OK_COMMAND);
        startAnalysisButton.addActionListener(this);
        startAnalysisButton.setText(">>> Run Analysis");


        add(new JLabel(""), "span 2");
        add(startAnalysisButton, "wrap");

        //add(startAnalysisButton, "span2, align right, wrap");

        if (System.getenv("QUALIMAP_DEBUG") != null) {
            bamPathEdit.setText("/home/kokonech/sample_data/paired_rna_seq/pe_nssp_hg19.chr20.bam");
            gffPathEdit.setText("/data/annotations/Homo_sapiens.GRCh37.68.chr20.gtf");

        }


        pack();
        updateState();


    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        if (actionEvent.getActionCommand().equals(Constants.OK_COMMAND)) {
            String errMsg = validateInput();
            if (!errMsg.isEmpty()) {
                JOptionPane.showMessageDialog(this, errMsg, getTitle(), JOptionPane.ERROR_MESSAGE);
                return;
            }

            TabPageController tabPageController = new TabPageController(AnalysisType.RNA_SEQ_QC);
            RNASeqQCAnalysisThread t = new RNASeqQCAnalysisThread(this, tabPageController);
            t.start();


        }  else if (actionEvent.getActionCommand().equals(Constants.CANCEL_COMMAND)) {
            setVisible(false);
        }  else {
            updateState();
        }


    }

    private void updateState() {
        countingAlgoCombo.setEnabled(advancedOptions.isSelected());
        countingMethodLabel.setEnabled(advancedOptions.isSelected());

        String countingMethod =  countingAlgoCombo.getSelectedItem().toString();
        String algorithmHint =  countingMethod.equals(ComputeCountsTask.COUNTING_ALGORITHM_ONLY_UNIQUELY_MAPPED)  ?
                "Only uniquely mapped reads will be consider" :
                "<html>Each read will be weighted according to its number" +
                " of mapped locations. <br>For example, a read with 4 mapping locations will " +
                "<br>add 0.25 to the counts on each location.</html>";

        countingAlgoCombo.setToolTipText(algorithmHint);


        String protocol =  strandTypeCombo.getSelectedItem().toString();
        if (protocol.equals(LibraryProtocol.PROTOCOL_NON_STRAND_SPECIFIC))  {
            strandTypeCombo.setToolTipText("Reads are counted if mapped to a feature independent of strand");
        }   else if (protocol.equals(LibraryProtocol.PROTOCOL_FORWARD_STRAND)) {
            strandTypeCombo.setToolTipText("<html>The single-end reads must have the same strand as the feature." +
                    "<br>For paired-end reads first of a pair must have the same strand as the feature," +
                    "<br>while the second read must be on the opposite strand</html>");
        } else {
            strandTypeCombo.setToolTipText("<html>The single-end reads must have the strand opposite to the feature." +
                    "<br>For paired-end reads first of a pair must have on the opposite strand," +
                    "<br>while the second read must be on the same strand as the feature.</html>");
        }


    }


    public void setUiEnabled(boolean  enabled) {
        for( Component c : getContentPane().getComponents()) {
            c.setEnabled(enabled);
        }

        /*if (enabled) {
            //boolean gtfSpecific =
                    DocumentUtils.guessFeaturesFileFormat(gffPathEdit.getText()) == FeatureFileFormat.GTF;
            //setGtfSpecificOptionsEnabled( gtfSpecific );
        } else {
            setGtfSpecificOptionsEnabled(false);
        }*/

        startAnalysisButton.setEnabled(enabled);
    }


    public String getBamFilePath() {
        return bamPathEdit.getText();
    }

    public String getGtfFilePath() {
        return gffPathEdit.getText();
    }

    public String getProtocol() {
        return strandTypeCombo.getSelectedItem().toString();
    }

    public String getCountingAlgorithm() {
        return countingAlgoCombo.getSelectedItem().toString();
    }

    public JTextArea getLogTextArea() {
        return logArea;
    }


    private String validateInput() {

        File bamFile = new File(bamPathEdit.getText());
        if (!bamFile.exists()) {
            return "BAM file is not found!";
        }

        File gffFile = new File(gffPathEdit.getText());
        if (!gffFile.exists())  {
            return "Annotations file is not found!";
        }

        /*File outputFile = new File(outputPathField.getText());
        try {
           if (!outputFile.exists() && !outputFile.createNewFile()) {
               throw new IOException();
           }
        } catch (IOException e) {
            return "Output file path is not valid!";
        }
        if (!outputFile.delete()) {
            return "Output path is not valid! Deleting probing directory failed.";
        }*/


        return "";
    }
}
