/**
 * QualiMap: evaluation of next generation sequencing alignment data
 * Copyright (C) 2012 Garcia-Alcalde et al.
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
package org.bioinfo.ngs.qc.qualimap.gui.utils;

import java.io.IOException;

import javax.swing.JLabel;

import org.bioinfo.commons.io.utils.FileUtils;
import org.bioinfo.commons.log.Logger;
import org.bioinfo.ngs.qc.qualimap.beans.BamQCRegionReporter;
import org.bioinfo.ngs.qc.qualimap.beans.BamStats;
import org.bioinfo.ngs.qc.qualimap.beans.GenomeLocator;
import org.bioinfo.ngs.qc.qualimap.gui.frames.HomeFrame;
import org.bioinfo.ngs.qc.qualimap.common.UniqueID;

/**
 * Value Object to manage the properties of each tab
 * @author Luis Miguel Cruz
 * @author Konstantin Okonechnikov
 */
public class TabPropertiesVO {
	/** Logger to print information */
	protected Logger logger;
	
	/** Variable that contains name of the folder of this tab VO */
	private StringBuilder outputFolder;
	
	/** Variable that informs if this tab has to show the gff graphics */
	private boolean gffSelected;
	
	/** Variable that contains name of the graphic image loaded into the
	 * right panel in the screen. */
    private String loadedGraphicName;

	/** Variable to manage the last link selected in the left menu, to remove the
	 * link decoration setted before. */
	private JLabel lastLinkSelected;


	/**
	 * Variable that contains the reporter generated by the BamQC algorithm.
	 */
	private BamQCRegionReporter reporter, outsideReporter;
	
	/**
	 * Variable that contains the GraphicImagePanel for each tab where the 
	 * system load the graphic image at each moment.
	 */
	private GraphicImagePanel graphicImage;
	
	/**
	 * Variable that contains the type of analysis that is done
	 */
	private AnalysisType typeAnalysis;
	
	/**
	 * Variable that contains the data for the tab of type RNA-Seq
	 */
	private RNAAnalysisVO rnaAnalysisVO;
	
	/**
	 * Variable to manage the last Element of the left split that contains
	 * the size, position, etc
	 */
	private boolean isPairedData;
    private boolean outsideStatsAvailable;
    private BamStats bamStats;
    private GenomeLocator genomeLocator;


    public void setBamStats(BamStats bamStats) {
        this.bamStats = bamStats;
    }


    public void setGenomeLocator(GenomeLocator genomeLocator) {
        this.genomeLocator = genomeLocator;
    }

	public boolean isPairedData() {
    	return isPairedData;
    }

	public void setPairedData(boolean isPairedData) {
    	this.isPairedData = isPairedData;
    }

    public void setOutsideStatsAvailable(boolean  outsideStatsAvailable) {
        this.outsideStatsAvailable = outsideStatsAvailable;
    }

    public boolean getOutsideStatsAvailable() {
        return outsideStatsAvailable;
    }

	public TabPropertiesVO(AnalysisType analysisType){
		this.typeAnalysis = analysisType;
		this.outputFolder = null;
		this.gffSelected = false;
		this.reporter = new BamQCRegionReporter();
        this.graphicImage = new GraphicImagePanel();
		this.rnaAnalysisVO = new RNAAnalysisVO();
        loadedGraphicName = "";
	}
	
	/*public TabPropertiesVO(StringBuilder outputFolder, boolean gffSelected){
		this.typeAnalysis = -1;
		this.outputFolder = outputFolder;
		this.gffSelected = gffSelected;
		this.graphicImage = new GraphicImagePanel();
		this.rnaAnalysisVO = new RNAAnalysisVO();
        loadedGraphicName = "";
	}*/
	
	/**
	 * Function to create an output folder to put the files generated by
	 * processed of the tab
	 * @return StringBuilder String with the path of the output folder
	 */
	public StringBuilder createDirectory(){
		boolean created = false;
		StringBuilder folderName = null;
		StringBuilder folderPath = new StringBuilder(HomeFrame.outputpath);
		StringBuilder outputDirPath = null;
		
		while(!created){
			try {
				folderName = new StringBuilder(""+UniqueID.get() + "/");
				outputDirPath = new StringBuilder(folderPath.toString() + folderName.toString());
				FileUtils.checkDirectory(outputDirPath.toString());
			} catch (IOException e) {
				if(outputDirPath != null){
					FileUtils.createDirectory(outputDirPath.toString(), true);
					this.setOutputFolder(folderName);
					
					created = true;
				}
			}
		}
		
		return outputDirPath;
	}

	public StringBuilder getOutputFolder() {
		return outputFolder;
	}

	public void setOutputFolder(StringBuilder outputFolder) {
		this.outputFolder = outputFolder;
	}

	public boolean isGffSelected() {
		return gffSelected;
	}

	public void setGffSelected(boolean gffSelected) {
		this.gffSelected = gffSelected;
	}

	public JLabel getLastLinkSelected() {
		return lastLinkSelected;
	}

	public void setLastLinkSelected(JLabel lastLinkSelected) {
		this.lastLinkSelected = lastLinkSelected;
	}

	public BamQCRegionReporter getReporter() {
		return reporter;
	}

	public void setReporter(BamQCRegionReporter reporter) {
		this.reporter = reporter;
	}

	public BamQCRegionReporter getOutsideReporter() {
		return outsideReporter;
	}

	public void setOutsideReporter(BamQCRegionReporter outsideReporter) {
		this.outsideReporter = outsideReporter;
	}

	public GraphicImagePanel getGraphicImage() {
		return graphicImage;
	}

	public AnalysisType getTypeAnalysis() {
		return typeAnalysis;
	}

	public RNAAnalysisVO getRnaAnalysisVO() {
		return rnaAnalysisVO;
	}

    public void setLoadedGraphicName(String loadedGraphicName) {
        this.loadedGraphicName = loadedGraphicName;
    }

    public String getLoadedGraphicName() {
        return loadedGraphicName;
    }


}
