package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisitorActorsTest {

    private final VisitorActors actors = new VisitorActors();

    @Test
    void isVisitorFollowedByEightHexCharacters() {
        assertThat(actors.actorFor("203.0.113.7")).matches("visitor-[0-9a-f]{8}");
    }

    @Test
    void theSameAddressKeepsItsNameAndAnotherAddressGetsAnother() {
        assertThat(actors.actorFor("203.0.113.7")).isEqualTo(actors.actorFor("203.0.113.7"));
        assertThat(actors.actorFor("203.0.113.7")).isNotEqualTo(actors.actorFor("203.0.113.8"));
    }

    @Test
    void doesNotContainTheAddress() {
        assertThat(actors.actorFor("203.0.113.7")).doesNotContain("203").doesNotContain("113");
    }

    @Test
    void theNameDependsOnASaltThatIsDifferentInEveryProcess() {
        assertThat(new VisitorActors().actorFor("203.0.113.7")).isNotEqualTo(actors.actorFor("203.0.113.7"));
    }

    @Test
    void fitsTheActorColumn() {
        assertThat(actors.actorFor("2001:db8::1").length()).isLessThanOrEqualTo(40);
    }
}
