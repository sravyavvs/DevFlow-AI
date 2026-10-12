package com.assessment.agentic.orchestration.sdlc;

/** Stage ids (also handler phases) of the SDLC pipeline. */
public final class SdlcStages {
    public static final String REQUIREMENTS = "REQUIREMENTS";
    public static final String CLARIFICATION = "CLARIFICATION";
    public static final String ARCHITECTURE = "ARCHITECTURE";
    public static final String TEST_PLAN = "TEST_PLAN";
    public static final String IMPLEMENTATION = "IMPLEMENTATION";
    public static final String TESTING = "TESTING";
    public static final String NFR_VERIFICATION = "NFR_VERIFICATION";
    public static final String DOCUMENTATION = "DOCUMENTATION";
    public static final String RELEASE_READINESS = "RELEASE_READINESS";

    private SdlcStages() {
    }
}
