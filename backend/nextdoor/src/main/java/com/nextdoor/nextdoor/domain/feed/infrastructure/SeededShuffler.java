package com.nextdoor.nextdoor.domain.feed.infrastructure;

import com.nextdoor.nextdoor.domain.feed.domain.service.Shuffler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Random;

@Component
public class SeededShuffler implements Shuffler {

    private final Random random;

    public SeededShuffler(@Value("${feed.shuffle.seed:0}") long seed) {
        this.random = new Random(seed);
    }

    @Override
    public <T> void shuffle(List<T> list) {
        Collections.shuffle(list, random);
    }
}