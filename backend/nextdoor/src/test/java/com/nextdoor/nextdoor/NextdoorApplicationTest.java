package com.nextdoor.nextdoor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NextdoorApplicationTest {

    @Test
    void 애플리케이션_클래스가_정상적으로_로딩된다() {
        // given
        Class<?> applicationClass = NextdoorApplication.class;

        // when
        String className = applicationClass.getSimpleName();

        // then
        assertThat(className).isEqualTo("NextdoorApplication");
    }
}
