package com.rsmaxwell.diaries.web.mqtt;

public enum EntityType {
    DIARY("diaries"),
    PAGE("pages"),
    FRAGMENT("fragments"),
    MARQUEE("marquees"),
    IMAGE("images");

    private final String topicSegment;

    EntityType(String topicSegment) {
        this.topicSegment = topicSegment;
    }

    public String topicSegment() {
        return topicSegment;
    }
}
