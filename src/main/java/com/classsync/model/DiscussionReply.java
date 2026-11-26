package com.classsync.model;

import java.time.Instant;

/** A reply to a doubt. professorAnswer = written by the course professor. */
public record DiscussionReply(long id, long discussionId, long authorId, String authorName, boolean professorAnswer,
                              String body, boolean hidden, Instant createdAt, int votes, boolean votedByMe) {
}
