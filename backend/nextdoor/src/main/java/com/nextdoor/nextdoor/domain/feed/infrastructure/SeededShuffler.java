package com.nextdoor.nextdoor.domain.feed.infrastructure;

import com.nextdoor.nextdoor.domain.feed.domain.service.Shuffler;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Random;

@Component
public class SeededShuffler implements Shuffler {

    @Override
    public <T> void shuffle(List<T> list, long seed) {
        Collections.shuffle(list, new Random(seed));
    }
}
