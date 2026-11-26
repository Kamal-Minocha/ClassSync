package com.classsync.model;

public record Topic(long id, long courseId, int unitNo, String name, int sequence) {
    @Override
    public String toString() {
        return "Unit " + unitNo + " · " + name;
    }
}
