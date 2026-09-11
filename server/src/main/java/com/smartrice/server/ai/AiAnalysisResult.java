package com.smartrice.server.ai;

public record AiAnalysisResult(AiEvidence evidence, String weatherAnalysis, String soilAnalysis,
	String riskAnalysis, String summary, String workflowRunId) {}
