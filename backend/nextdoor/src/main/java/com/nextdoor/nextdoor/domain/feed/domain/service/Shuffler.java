package com.nextdoor.nextdoor.domain.feed.domain.service;

import java.util.List;

public interface Shuffler {
    <T> void shuffle(List<T> list);
}