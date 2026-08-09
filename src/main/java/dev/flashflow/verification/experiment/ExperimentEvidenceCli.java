package dev.flashflow.verification.experiment;

import java.nio.file.Path;

public final class ExperimentEvidenceCli {
    private ExperimentEvidenceCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"report".equals(args[0])) {
            throw new IllegalArgumentException("Usage: report <run-directory>");
        }
        Path runDirectory = Path.of(args[1]);
        ExperimentRunEvidence evidence = ExperimentEvidenceReporter.read(runDirectory);
        ExperimentEvidenceReporter.write(runDirectory, evidence);
        System.out.println(evidence.status());
    }
}
