package com.chiru.engine.core;

/** One independently testable step of the pipeline (discovery, Spoon, SQL, lineage, ...). */
public interface AnalysisStage {

    String name();

    void run(StageContext context) throws Exception;
}
