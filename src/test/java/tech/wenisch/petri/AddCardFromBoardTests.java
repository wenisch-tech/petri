package tech.wenisch.petri;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adding a card from the board, as a signed-in person rather than a script. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AddCardFromBoardTests {

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;

    private MockMvc mockMvc;
    private Board board;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        board = new Board();
        board.setSlug("add-card");
        board.setName("Add Card");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);
    }

    private void pipeline() {
        WorkflowState first = new WorkflowState();
        first.setBoard(board);
        first.setName("planner");
        first.setPosition(0);
        first.setGate(GateType.PLAN_SHAPE);
        first.setModelAlias("chatgpt");
        states.save(first);
    }

    @Test
    void aCardLandsInTheFirstState() throws Exception {
        pipeline();

        mockMvc.perform(post("/boards/add-card/cards")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("title", "Bound the turn by silence")
                        .param("description", "Replace the wall-clock timeout."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/cards/*"));

        assertThat(cards.findByBoardOrderByIdAsc(board))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.getTitle()).isEqualTo("Bound the turn by silence");
                    assertThat(card.getState().getName()).isEqualTo("planner");
                });
    }

    @Test
    void aBoardWithNoStatesSaysSoInsteadOfFailing() throws Exception {
        mockMvc.perform(post("/boards/add-card/cards")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("title", "Nowhere to go"))
                .andExpect(status().is3xxRedirection());

        assertThat(cards.findByBoardOrderByIdAsc(board)).isEmpty();
    }

    @Test
    void addingACardRequiresSigningIn() throws Exception {
        pipeline();

        mockMvc.perform(post("/boards/add-card/cards").with(csrf())
                        .param("title", "Not signed in"))
                .andExpect(status().is3xxRedirection());

        assertThat(cards.findByBoardOrderByIdAsc(board)).isEmpty();
    }

    @Test
    void aFormPostWithoutACsrfTokenIsRejected() throws Exception {
        pipeline();

        // The browser chain keeps CSRF on precisely because it is cookie-backed.
        mockMvc.perform(post("/boards/add-card/cards")
                        .with(user("admin").roles("VIEWER"))
                        .param("title", "Forged"))
                .andExpect(status().isForbidden());

        assertThat(cards.findByBoardOrderByIdAsc(board)).isEmpty();
    }
}
