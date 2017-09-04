package org.snpeff.snpEffect.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.snpeff.genBank.Feature;
import org.snpeff.genBank.Feature.Type;
import org.snpeff.genBank.FeatureCoordinates;
import org.snpeff.genBank.Features;
import org.snpeff.genBank.FeaturesFile;
import org.snpeff.interval.Cds;
import org.snpeff.interval.Chromosome;
import org.snpeff.interval.CircularCorrection;
import org.snpeff.interval.Exon;
import org.snpeff.interval.Gene;
import org.snpeff.interval.Transcript;
import org.snpeff.snpEffect.Config;
import org.snpeff.snpEffect.SnpEffectPredictor;
import org.snpeff.util.Gpr;
import org.snpeff.util.GprSeq;

/**
 * This class creates a SnpEffectPredictor from a 'features' file.
 * This includes derived formats as GenBank and Embl.
 *
 * References:
 * 		http://www.ebi.ac.uk/embl/Documentation/User_manual/printable.html
 * 		http://www.ebi.ac.uk/embl/Documentation/FT_definitions/feature_table.html
 *
 *
 * @author pcingola
 */
public abstract class SnpEffPredictorFactoryFeatures extends SnpEffPredictorFactory {

	public static final int OFFSET = 1;
	Chromosome chromosome; // It is assumed that there is only one 'Chromosome' (i.e. only one 'SOURCE' feature)
	FeaturesFile featuresFile;
	Map<String, String> proteinByTrId;

	public SnpEffPredictorFactoryFeatures(Config config) {
		super(config, OFFSET);
		proteinByTrId = new HashMap<>();
	}

	/**
	 * Add CDS and protein coding information
	 */
	Transcript addCds(Feature fcds, Gene geneLatest, List<Transcript> trLatest) {
		// Find (or create) transcript
		Transcript tr = findTrForCds(fcds, geneLatest, trLatest);
		//
		// Mark transcript as protein coding
		if (fcds.getAasequence() != null) tr.setProteinCoding(true);

		// Check and set ribosomal slippage
		if (fcds.get("ribosomal_slippage") != null) tr.setRibosomalSlippage(true);

		// Add exons?
		if (fcds.hasMultipleCoordinates()) {
			for (FeatureCoordinates fc : fcds) {
				int cdsStart = fc.start - inOffset;
				int cdsEnd = fc.end - inOffset;
				Cds cds = new Cds(tr, cdsStart, cdsEnd, fcds.isComplement(), "CDS_" + tr.getId());
				add(cds);
			}

			// Circular correction
			CircularCorrection cc = new CircularCorrection(tr);
			cc.correct();
		} else {
			Cds cds = new Cds(tr, fcds.getStart() - inOffset, fcds.getEnd() - inOffset, fcds.isComplement(), "CDS_" + tr.getId());
			add(cds);
		}

		// Add transcript - protein sequence mapping
		proteinByTrId.put(tr.getId(), fcds.getAasequence());

		return tr;
	}

	/**
	 *	Add all features
	 */
	protected void addFeatures(Features features) {
		//---
		// Add chromosome
		//---
		for (Feature f : features.getFeatures()) {
			// Convert coordinates to zero-based
			int start = f.getStart() - inOffset;
			int end = f.getEnd() - inOffset;

			// Add chromosome
			if (f.getType() == Type.SOURCE) {
				if (chromosome == null) {
					String chrName = chromoName(features, f);
					chromosome = new Chromosome(genome, start, end, chrName);
					add(chromosome);
				} else {
					if (debug) System.err.println("Warnign: 'SOURCE' already assigned to chromosome. Ignoring feature:\n" + f);
				}
			}
		}

		// No 'SOURCE' entry found? May be locusName is available.
		if (chromosome == null) {
			String chrName = chromoName(features, null);
			int chrSize = sequence(features).length();
			chromosome = new Chromosome(genome, 0, chrSize, chrName);
			add(chromosome);
		}

		// Sanity check
		if (chromosome == null) throw new RuntimeException("Could not find SOURCE feature");
		if (verbose) System.err.println("Chromosome: '" + chromosome.getId() + "'\tlength: " + chromosome.size());

		//---
		// Add a genes, transcripts and CDSs
		//---
		Gene geneLatest = null;
		List<Transcript> trLatest = null;
		for (Feature f : features.getFeatures()) {
			if (f.getType() == Type.GENE) {
				// Add gene
				geneLatest = findOrCreateGene(f, chromosome, false);
				trLatest = null;
			} else {
				Transcript trl = null;

				// Add feature
				if (f.getType() == Type.MRNA) trl = addMrna(f, geneLatest);
				else if (f.getType() == Type.CDS) trl = addCds(f, geneLatest, trLatest);

				// Added transcript?
				if (trl != null) {
					// If we are using another gene, then 'geneLatest' should change
					if (geneLatest == null //
							|| trLatest == null //
							|| !trl.getParent().getId().equals(geneLatest.getId()) // New gene? i.e. gen IDs do not math
					) {
						// Create new transcripts list
						trLatest = new ArrayList<>();
						trLatest.add(trl);
					} else trLatest.add(trl);

					geneLatest = (Gene) trl.getParent();
				}
			}
		}
	}

	/**
	 * Add transcript information
	 */
	Transcript addMrna(Feature f, Gene geneLatest) {
		if (debug) Gpr.debug("Feature:" + f);
		// Convert coordinates to zero-based
		int start = f.getStart() - inOffset;
		int end = f.getEnd() - inOffset;

		// Get gene: Make sure the transcript actually refers to the latest gene.
		Gene gene = null;
		if ((geneLatest != null) && geneLatest.intersects(start, end)) gene = geneLatest;
		else gene = findOrCreateGene(f, chromosome, false); // Find or create gene

		// Add transcript
		String trId = f.getTranscriptId();
		Transcript tr = new Transcript(gene, start, end, f.isComplement(), trId);

		// Add exons?
		if (f.hasMultipleCoordinates()) {
			int exNum = 1;
			for (FeatureCoordinates fc : f) {
				Exon e = new Exon(tr, fc.start - inOffset, fc.end - inOffset, fc.complement, tr.getId() + "_" + exNum, exNum);
				tr.add(e);
				exNum++;
			}
		}

		add(tr);
		return tr;
	}

	/**
	 * Does the transcript match the latest gene?
	 */
	boolean cdsMatchesGene(Feature fcds, Gene gene) {
		if (gene == null) return false;

		// Convert coordinates to zero-based
		int start = fcds.getStart() - inOffset;
		int end = fcds.getEnd() - inOffset;

		// CDS cannot be outside gene coordinates
		if (start < gene.getStart() || gene.getEnd() < end) return false;

		// Sanity check: Do gene names match?
		String geneName = fcds.getGeneName();

		// Gene names do no match, we are not in the same transcript
		return geneName != null //
				&& gene != null //
				&& gene.getGeneName().equals(geneName) // No match?
		;
	}

	/**
	 * Does this CDS feature match the latest transcript ?
	 */
	boolean cdsMatchesTr(Feature fcds, Transcript tr) {
		if (tr == null) return false;

		// Convert coordinates to zero-based
		int start = fcds.getStart() - inOffset;
		int end = fcds.getEnd() - inOffset;

		// CDS cannot be outside transcript coordinates
		if (start < tr.getStart() || tr.getEnd() < end) return false;

		// If multiple coordinates are available (and transcript has exons), try to match exons
		if (fcds.hasMultipleCoordinates() && !tr.subIntervals().isEmpty()) return cdsMatchesTrExons(fcds, tr);
		return true;
	}

	/**
	 * Does this CDS feature match the transcript coordinates?
	 */
	boolean cdsMatchesTrExons(Feature fcds, Transcript tr) {
		// Sorted list of exons from CDS
		List<Exon> cdsExons = new ArrayList<>();
		for (FeatureCoordinates fc : fcds) {
			Exon e = new Exon(tr, fc.start - inOffset, fc.end - inOffset, fc.complement, "", -1);
			cdsExons.add(e);
		}
		Collections.sort(cdsExons);

		// Sorted list of exons from transcript
		List<Exon> trExons = new ArrayList<>();
		trExons.addAll(tr.subIntervals());
		Collections.sort(trExons);

		// Do CDS exons match transcript exons?
		if (cdsExons.size() > trExons.size()) return false; // Transcript must have at least as many exons as CDS

		// Do exons match
		return cdsMatchesTrExons(cdsExons, trExons, tr);
	}

	/**
	 * Do CDS exon match transcript exons?
	 * @param cdsExons : List of CDS exons sorted by start position
	 * @param trExons : List of transcript exons sorted by start position
	 * @param tr : Transcript
	 * @return true if the list of CDS exons fits in transcript exons
	 */
	boolean cdsMatchesTrExons(List<Exon> cdsExons, List<Exon> trExons, Transcript tr) {
		// Special case: CDS has only one exon
		if (cdsExons.size() == 1) {
			Exon cdsEx = cdsExons.get(0);
			Exon trEx = tr.findExon(cdsEx);
			return (trEx != null) && (trEx.includes(cdsEx));
		}

		int cdsExIdx = 0, trExIdx = 0;
		Exon cdsEx = cdsExons.get(cdsExIdx);
		Exon trEx = trExons.get(trExIdx);

		// Skip transcript exons before CDs
		while (cdsEx.getStart() > trEx.getEnd()) {
			trExIdx++;
			if (trExIdx >= trExons.size()) return false; // We run out of transcript's exons and none matched
			trEx = trExons.get(trExIdx);
		}

		// First CDS exon can differ only on the left side.
		if ((trEx.getStart() > cdsEx.getStart()) || (trEx.getEnd() != cdsEx.getEnd())) return false;

		// Exons after the first (and before the last one) must match exactly
		while (cdsExIdx < cdsExons.size() - 2) {
			cdsExIdx++;
			trExIdx++;
			if (trExIdx >= trExons.size()) return false; // We run out of transcript's exons and none matched
			cdsEx = cdsExons.get(cdsExIdx);
			trEx = trExons.get(trExIdx);
			if ((trEx.getStart() != cdsEx.getStart()) || (trEx.getEnd() != cdsEx.getEnd())) return false;
		}

		// Compare last CDS exon
		cdsExIdx++;
		trExIdx++;
		if (cdsExIdx >= cdsExons.size()) return true; // Finished comparing
		if (trExIdx >= trExons.size()) return false; // We run out of transcript's exons
		cdsEx = cdsExons.get(cdsExIdx);
		trEx = trExons.get(trExIdx);

		// Last CDS exon can differ only on the right side.
		if ((trEx.getStart() != cdsEx.getStart()) || (trEx.getEnd() < cdsEx.getEnd())) return false;

		return true; // OK, all CDS exons match
	}

	/**
	 * Find or create a chromosome name for a feature
	 */
	String chromoName(Features features, Feature sourceFeature) {
		// Try 'chromosome' from SOURCE feature
		if (sourceFeature != null) {
			if (sourceFeature.getType() != Type.SOURCE) throw new RuntimeException("Cannot find chromosome name in a non-SOURCE feature");
			String chrName = sourceFeature.get("chromosome");
			if (chrName != null) return chrName;
		}

		// Try locusName
		String chrName = features.getLocusName();
		if (chrName != null) return chrName;

		return genome.getId();
	}

	@Override
	public SnpEffectPredictor create() {
		// Read gene intervals from a file
		try {
			// Iterate over all features
			for (Features features : featuresFile) {
				chromosome = null; // Make sure we create a new source for each file
				addFeatures(features);

				// Some clean-up before reading exon sequences
				beforeExonSequences();

				// Get exon sequences
				String sequence = sequence(features);
				addSequences(chromosome.getId(), sequence);
			}

			// Finish up (fix problems, add missing info, etc.)
			finishUp();
		} catch (Exception e) {
			if (verbose) e.printStackTrace();
			throw new RuntimeException("Error reading file '" + fileName + "'\n" + e);
		}

		return snpEffectPredictor;
	}

	/**
	 * Find (or create) a gene from a feature
	 */
	Gene findOrCreateGene(Feature f, Chromosome chr, boolean warn) {
		int start = f.getStart() - inOffset;
		int end = f.getEnd() - inOffset;

		String geneId = geneId(f, start, end);
		String geneName = geneName(f, start, end);

		Gene gene = findGene(geneId);
		if (gene == null) {
			gene = new Gene(chr, start, end, f.isComplement(), geneId, geneName, null);
			add(gene);
			if (debug) System.err.println("WARNING: Gene '" + geneId + "' not found: created.");
		}

		return gene;
	}

	/**
	 * Find (or create) a transcript for this CDS feature
	 */
	Transcript findTrForCds(Feature fcds, Gene geneLatest, List<Transcript> trLatest) {
		// Try to find the 'latest' gene / transcript
		Transcript trLatestMatch = findTrFromLatest(fcds, geneLatest, trLatest);
		if (trLatestMatch != null) return trLatestMatch;

		// Try to find transcript by id
		String trId = fcds.getTranscriptId();
		Transcript tr = findTranscript(trId);
		if (tr != null) return tr;

		// Nothing found, create gene and transcript
		int start = fcds.getStart() - inOffset; // Convert coordinates to zero-based
		int end = fcds.getEnd() - inOffset;

		// Create gene (or use latest)
		Gene gene = null;
		if (cdsMatchesGene(fcds, geneLatest)) gene = geneLatest;
		else gene = findOrCreateGene(fcds, chromosome, false);

		// Create transcript
		if (trId == null) trId = "Tr_" + start + "_" + end;
		tr = findTranscript(trId);
		if (tr == null) {
			if (debug) System.err.println("Transcript '" + trId + "' not found. Creating new transcript for gene '" + gene.getId() + "'.\n" + fcds);
			tr = new Transcript(gene, start, end, fcds.isComplement(), trId);
			add(tr);
		}

		return tr;
	}

	/**
	 * Does the CDS feature match the latest gene / transcript?
	 * @return Matching transcript, null if not found
	 */
	Transcript findTrFromLatest(Feature fcds, Gene geneLatest, List<Transcript> trLatest) {
		// Does CDS match latest gene?
		if (cdsMatchesGene(fcds, geneLatest)) {
			// Find first matching transcript without CDS information
			for (Transcript tr : trLatest) {
				if (tr.getCds().isEmpty() && cdsMatchesTr(fcds, tr)) { return tr; }
			}
		}

		return null; // No match
	}

	/**
	 * Try to get geneIDs
	 */
	protected String geneId(Feature f, int start, int end) {
		// Try 'locus'...
		String geneId = f.getGeneId();
		if (geneId != null) return geneId;

		return "Gene_" + start + "_" + end;
	}

	/**
	 * Get gene name from feature
	 */
	protected String geneName(Feature f, int start, int end) {
		// Try 'gene'...
		String geneName = f.getGeneName();
		if (geneName != null) return geneName;

		return "Gene_" + start + "_" + end;
	}

	public Map<String, String> getProteinByTrId() {
		return proteinByTrId;
	}

	/**
	 * Get sequence either from features or from FASTA file
	 */
	String sequence(Features features) {
		String seq = features.getSequence();
		if ((seq != null) && !seq.isEmpty()) return seq;
		if (verbose) System.out.println("No sequence found in feature file.");

		// No sequence information in 'features' file? => Try to read a sequence from a fasta file
		for (String fastaFile : config.getFileListGenomeFasta()) {
			if (verbose) System.out.println("\tTrying fasta file '" + fastaFile + "'");

			if (Gpr.canRead(fastaFile)) {
				seq = GprSeq.fastaSimpleRead(fastaFile);
				if ((seq != null) && !seq.isEmpty()) return seq;
			}
		}

		throw new RuntimeException("Cannot find sequence for '" + config.getGenome().getVersion() + "'");
	}
}
