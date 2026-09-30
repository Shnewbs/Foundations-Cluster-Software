package com.foundations.cluster.persistence;

public enum RegionFileKind {
    REGION("region"),
    ENTITIES("entities"),
    POI("poi");

    private final String directory;

    RegionFileKind(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}
