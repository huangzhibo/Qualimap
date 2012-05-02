package org.bioinfo.ngs.qc.qualimap.beans;

import org.bioinfo.commons.utils.ArrayUtils;
import org.bioinfo.math.util.MathUtils;

import javax.swing.*;


public class BamDetailedGenomeWindow extends BamGenomeWindow {
	// reference sequence	
	private byte[] reference;
		
	// coverageData
	private int[] coverageAcrossReference;

	// quality
	private long[] mappingQualityAcrossReference;
			
	// A content
	private long[] aContentAcrossReference;
	
	// C content
	private long[] cContentAcrossReference;

	// G content
	private long[] gContentAcrossReference;
	
	// T content
	private long[] tContentAcrossReference;
	
	// N content
	private long[] nContentAcrossReference;
		
	// GC content
	private double[] gcContentAcrossReference;
		
	// AT content
//	private double[] atContentAcrossReference;

    // required for calculation of global coverageData
    private long sumCoverageSquared;

	
	public BamDetailedGenomeWindow(String name, long start, long end){
		this(name,start,end,null);		
	}

	public BamDetailedGenomeWindow(String name, long start, long end, byte[] reference){
		super(name,start,end,reference);
		
		if(reference!=null){
			this.reference = reference;
		}

        // init arrays
		coverageAcrossReference = new int[(int)this.windowSize];
		mappingQualityAcrossReference = new long[(int)this.windowSize];
//		sequencingQualityAcrossReference = new int[this.windowSize];
//		aContentAcrossReference = new long[(int)this.windowSize];
//		cContentAcrossReference = new long[(int)this.windowSize];
//		gContentAcrossReference = new long[(int)this.windowSize];
//		tContentAcrossReference = new long[(int)this.windowSize];
//		nContentAcrossReference = new long[(int)this.windowSize];
//		gcContentAcrossReference = new double[(int)this.windowSize];
//		atContentAcrossReference = new double[(int)this.windowSize];

	}
	
	@Override
	protected void acumBase(long relative){
		super.acumBase(relative);
		coverageAcrossReference[(int)relative] = coverageAcrossReference[(int)relative]+1;
	}
	
	@Override
	protected void acumProperlyPairedBase(long relative){
		super.acumProperlyPairedBase(relative);
		//TODO: why collect it?
		//properlyPairedCoverageAcrossReference[(int)relative] = properlyPairedCoverageAcrossReference[(int)relative] + 1;
	}

	@Override
	protected void acumA(long relative){
		super.acumA(relative);
		aContentAcrossReference[(int)relative] = aContentAcrossReference[(int)relative]+1;
	}
	
	@Override
	protected void acumC(long relative){
		super.acumC(relative);
		cContentAcrossReference[(int)relative] = cContentAcrossReference[(int)relative]+1;
	}
	
	@Override
	protected void acumT(long relative){
		super.acumT(relative);
		tContentAcrossReference[(int)relative] = tContentAcrossReference[(int)relative]+1;
	}
	
	@Override
	protected void acumG(long relative){
		super.acumG(relative);
		gContentAcrossReference[(int)relative] = gContentAcrossReference[(int)relative]+1;
	}
	
	@Override
	protected void acumMappingQuality(long relative, int mappingQuality){
		super.acumMappingQuality(relative,mappingQuality);		
		// quality					
		mappingQualityAcrossReference[(int)relative] = mappingQualityAcrossReference[(int)relative]+mappingQuality;
	}

	@Override
	protected void acumInsertSize(long relative, long insertSize){
		super.acumInsertSize(relative,insertSize);
	}
	
	@Override
	public void computeDescriptors() throws CloneNotSupportedException{
	
		// normalize vectors
		for(int i=0; i<coverageAcrossReference.length; i++){

			long coverageAtPosition =  coverageAcrossReference[i];

            if(coverageAtPosition > 0){

				// quality
				mappingQualityAcrossReference[i] = mappingQualityAcrossReference[i] / coverageAtPosition;
                // insert size
                //  System.err.println(properlyPairedCoverageAcrossReference[i]);
                //  insertSizeAcrossReference[i] = insertSizeAcrossReference[i]/(double)properlyPairedCoverageAcrossReference[i];

                sumCoverageSquared += coverageAtPosition*coverageAtPosition;

                //numberOfProperlyPairedMappedBases++;
				
			} else {
                // make it invalid for histogram
                 mappingQualityAcrossReference[i] = -1;
            }

		}
				
		// compute std coverageData
		stdCoverage = MathUtils.standardDeviation(ArrayUtils.toDoubleArray(coverageAcrossReference));

		super.computeDescriptors();
		
	}

    @Override
    public void addReadData(SingleReadData readData) {
        super.addReadData(readData);
        for (int pos : readData.coverageData) {
            coverageAcrossReference[pos]++;
        }

        for (SingleReadData.Cell cell : readData.mappingQualityData) {
            int pos = cell.getPosition();
            int val = cell.getValue();
            mappingQualityAcrossReference[pos] += val;
            super.acumMappingQuality += val;
        }

    }

    /**
	 * @return the reference
	 */
	public byte[] getReference() {
		return reference;
	}

	/**
	 * @return the coverageAcrossReference
	 */
	public int[] getCoverageAcrossReference() {
		return coverageAcrossReference;
	}

    public long getSumCoverageSquared() {
        return sumCoverageSquared;
    }

	/**
	 * @return the mappingQualityAcrossReference
	 */
	public long[] getMappingQualityAcrossReference() {
		return mappingQualityAcrossReference;
	}

	//TODO: optimize it.
	// Some data is not even used. Why calculate it?

    /**
	 * @return the aContentAcrossReference
	 */
	public long[] getaContentAcrossReference() {
		return aContentAcrossReference;
	}

	/**
	 * @return the cContentAcrossReference
	 */
	public long[] getcContentAcrossReference() {
		return cContentAcrossReference;
	}

	/**
	 * @return the gContentAcrossReference
	 */
	public long[] getgContentAcrossReference() {
		return gContentAcrossReference;
	}

	/**
	 * @return the tContentAcrossReference
	 */
	public long[] gettContentAcrossReference() {
		return tContentAcrossReference;
	}

	/**
	 * @return the nContentAcrossReference
	 */
	public long[] getnContentAcrossReference() {
		return nContentAcrossReference;
	}

	/**
	 * @return the gcContentAcrossReference
	 */
	public double[] getGcContentAcrossReference() {
		return gcContentAcrossReference;
	}






}