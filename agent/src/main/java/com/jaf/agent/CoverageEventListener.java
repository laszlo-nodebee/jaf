package com.jaf.agent;

@FunctionalInterface
interface CoverageEventListener {
    void onNewEdge(int edgeId);
}
