package MCEM;

import arc.*;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.*;
import mindustry.core.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.mod.*;
import mindustry.ui.*;

import java.util.HashSet;
import java.util.Set;

import static arc.Core.*;
import static mindustry.Vars.*;

public class Main extends Mod {
    private static final String buildAssistSetting = "multiplayerpause-buildassist";

    private final Set<Integer> pauseVotes = new HashSet<>();
    private Boolean voteTarget;
    private boolean applyingPauseVoteState;
    private KeyBind buildAssistKey;
    private Label buildAssistHint;
    private Table buildAssistFallback;

    @Override
    public void init() {
        buildAssistKey = KeyBind.add("multiplayerpause_buildassist", KeyCode.y, "multiplayerpause");
        addSettings();
        setupEvents();
        setupPackets();
    }

    void addSettings() {
        ui.settings.addCategory("Multiplayer Pause", Icon.pause, s -> {
            s.checkPref("multiplayerpause-toasts", true);
            s.checkPref("multiplayerpause-allowany", true);
            s.checkPref("multiplayerpause-adminsobeysync", false);
            s.checkPref("multiplayerpause-synconpause", false);
            s.checkPref("multiplayerpause-synconunpause", false); // Is enabling this by default a good idea? I have no clue how much desync this mod is going to cause...
            s.checkPref("multiplayerpause-schedulesync", false);
        });
    }

    void setupEvents() {
        Events.run(Trigger.update, () -> {
            ensureBuildAssistHint();
            if (state.isGame() && player != null && player.isBuilder() && !scene.hasDialog() && !scene.hasKeyboard() &&
                Core.input.keyTap(buildAssistKey)) {
                toggleBuildAssist();
            }
            updateBuildAssist();

            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() && !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active() && net.client()) {
                Call.serverPacketReliable("multiplayerpause-request", ""); // Send pause request
            }
        });
    }

    void ensureBuildAssistHint() {
        if (headless || !state.isGame() || Core.scene == null) return;
        if (Core.settings.getBool("hints") && buildAssistHint != null && buildAssistHint.parent != null) {
            removeBuildAssistFallback();
            return;
        }

        if (!Core.settings.getBool("hints")) {
            ensureBuildAssistFallback();
            return;
        }

        buildAssistHint = null;
        Table[] hintPanel = {null};
        Core.scene.root.forEach(element -> {
            if (element instanceof Label label && isBuildInstructionLabel(label) && label.parent instanceof Table table) {
                hintPanel[0] = table;
            }
        });
        if (hintPanel[0] == null) {
            ensureBuildAssistFallback();
            return;
        }

        hintPanel[0].row();
        buildAssistHint = hintPanel[0].label(this::buildAssistHintText).style(Styles.outlineLabel).left().get();
        buildAssistHint.name = "mcem-build-assist-hint";
        buildAssistHint.clicked(this::toggleBuildAssist);
        removeBuildAssistFallback();
    }

    void ensureBuildAssistFallback() {
        if (buildAssistFallback != null && buildAssistFallback.parent != null) return;

        buildAssistFallback = new Table();
        buildAssistFallback.setFillParent(true);
        buildAssistFallback.bottom().left();
        buildAssistFallback.table(Styles.black6, panel -> {
            ImageButton button = panel.button(Icon.hammer, Styles.clearNoneTogglei, this::toggleBuildAssist)
                .size(40f).pad(4f).get();
            button.update(() -> button.setChecked(Core.settings.getBool(buildAssistSetting, false)));
            panel.label(this::buildAssistHintText).padLeft(4f).padRight(8f);
        }).left().padLeft(8f).padBottom(8f);
        buildAssistFallback.visible(() -> state.isGame() && player != null && player.isBuilder() &&
            (!Core.settings.getBool("hints") || buildAssistHint == null || buildAssistHint.parent == null));
        Core.scene.root.addChild(buildAssistFallback);
    }

    void removeBuildAssistFallback() {
        if (buildAssistFallback == null) return;
        if (buildAssistFallback.parent != null) buildAssistFallback.parent.removeChild(buildAssistFallback);
        buildAssistFallback = null;
    }

    boolean isBuildInstructionLabel(Label label) {
        String text = label.getText().toString();
        return text.contains(Core.bundle.format("pausebuilding", Binding.pauseBuilding.value.key.toString())) ||
            text.contains(Core.bundle.format("resumebuilding", Binding.pauseBuilding.value.key.toString())) ||
            text.contains(Core.bundle.format("enablebuilding", Binding.pauseBuilding.value.key.toString()));
    }

    String buildAssistHintText() {
        boolean enabled = Core.settings.getBool(buildAssistSetting, false);
        String hintKey = enabled ? "multiplayerpause-buildassist.hint-off" : "multiplayerpause-buildassist.hint-on";
        return Core.bundle.format(hintKey, buildAssistKey.value.key.getName());
    }

    void toggleBuildAssist() {
        boolean enabled = !Core.settings.getBool(buildAssistSetting, false);
        Core.settings.put(buildAssistSetting, enabled);
        Menus.infoToast(Core.bundle.get("multiplayerpause-buildassist." + (enabled ? "enabled" : "disabled")), 2f);
    }

    void updateBuildAssist() {
        if (!Core.settings.getBool(buildAssistSetting, false) || !state.isGame() || state.isPaused() ||
            player == null || player.dead() || !player.isBuilder()) return;

        Unit builder = player.unit();
        if (builder == null || !builder.canBuild() || !builder.updateBuilding) return;

        for (Player teammate : Groups.player) {
            if (teammate == player || teammate.team() != player.team() || teammate.dead() || !teammate.isBuilder()) continue;

            for (BuildPlan plan : teammate.getPreviewPlans()) {
                if (plan == null || plan.breaking || plan.block == null || !builder.within(plan, builder.type.buildRange)) continue;
                if (!hasPlanAt(builder, plan)) builder.plans().addLast(plan.copy());
            }
        }
    }

    boolean hasPlanAt(Unit builder, BuildPlan candidate) {
        for (BuildPlan plan : builder.plans()) {
            if (plan.x == candidate.x && plan.y == candidate.y) return true;
        }
        return false;
    }

    void setupPackets() {
        Events.on(StateChangeEvent.class, event -> {
            if (applyingPauseVoteState || !net.server() || !net.active() || !Core.input.keyTap(Binding.pause) ||
                (event.from != GameState.State.paused && event.from != GameState.State.playing) ||
                (event.to != GameState.State.paused && event.to != GameState.State.playing) ||
                renderer.isCutscene() || scene.hasDialog() || scene.hasKeyboard() || ui.restart.isShown() ||
                (state.isCampaign() && state.afterGameOver) || state.rules.pauseDisabled || player == null) return;

            boolean targetPaused = event.to == GameState.State.paused;
            if (isSyncEnabled(targetPaused) && (!player.admin || Core.settings.getBool("multiplayerpause-adminsobeysync"))) {
                boolean completed = castPauseVote(player, targetPaused);
                if (!completed) {
                    GameState.State stateBeforeVote = event.from;
                    Core.app.post(() -> {
                        if (voteTarget != null && voteTarget == targetPaused && state.getState() != stateBeforeVote) {
                            setPauseVoteState(stateBeforeVote);
                        }
                    });
                }
            } else {
                clearPauseVotes();
                showToast(player, targetPaused);
            }
        });

        netServer.addPacketHandler("multiplayerpause-request", (p, data) -> {
            if ((!p.admin && !Core.settings.getBool("multiplayerpause-allowany")) || state.isMenu() || state.rules.pauseDisabled) return;

            boolean targetPaused = !state.isPaused();
            if (p.admin && !Core.settings.getBool("multiplayerpause-adminsobeysync")) {
                clearPauseVotes();
                setPauseVoteState(targetPaused ? GameState.State.paused : GameState.State.playing);
                showToast(p, targetPaused);
            } else {
                castPauseVote(p, targetPaused);
            }
        });
        // State changes are forwarded to clients for more responsive pausing (avoids waiting for next stateSnapshot) which should reduce desync (I hope) and allows for toasts
        netClient.addPacketHandler("multiplayerpause-updatestate", data -> {
            String[] d = data.split(" ");
            if (d.length != 2) return;
            boolean paused = d[1].equals("t");
            state.set(paused ? GameState.State.paused : GameState.State.playing); // Reflect state change on the client ASAP
            showToast(Groups.player.getByID(Strings.parseInt(d[0])), paused);
        });
        netClient.addPacketHandler("multiplayerpause-votes", data -> {
            String[] d = data.split(" ");
            if (d.length != 3 || !(d[0].equals("p") || d[0].equals("u"))) return;

            int votes;
            int total;
            try {
                votes = Integer.parseInt(d[1]);
                total = Integer.parseInt(d[2]);
            } catch (NumberFormatException ignored) {
                Log.warn("Ignoring malformed pause vote update: @", data);
                return;
            }

            if (votes < 0 || total < 0 || votes > total) {
                Log.warn("Ignoring invalid pause vote count: @", data);
                return;
            }

            showVoteProgressLocally(d[0].equals("p"), votes, total);
        });

        Events.on(PlayerLeave.class, event -> {
            if (net.server()) Core.app.post(this::refreshVotesAfterLeave);
        });
    }

    boolean isSyncEnabled(boolean pausing) {
        return Core.settings.getBool(pausing ? "multiplayerpause-synconpause" : "multiplayerpause-synconunpause");
    }

    boolean castPauseVote(Player p, boolean targetPaused) {
        if (p == null) return false;

        if (!isSyncEnabled(targetPaused)) {
            clearPauseVotes();
            setPauseVoteState(targetPaused ? GameState.State.paused : GameState.State.playing);
            showToast(p, targetPaused);
            return true;
        }

        if (voteTarget == null || voteTarget != targetPaused) {
            clearPauseVotes();
            voteTarget = targetPaused;
        }

        pauseVotes.removeIf(id -> !isVotePlayerConnected(id));
        pauseVotes.add(p.id);
        return updateVoteProgress();
    }

    boolean updateVoteProgress() {
        if (voteTarget == null) return false;

        int total = votePlayerCount();
        int votes = pauseVotes.size();
        boolean pausing = voteTarget;
        showVoteProgress(pausing, votes, total);

        if (total > 0 && votes >= total) {
            int voterId = pauseVotes.iterator().next();
            Player voter = Groups.player.getByID(voterId);
            if (voter == null && net.server() && player != null && player.id == voterId) voter = player;
            setPauseVoteState(pausing ? GameState.State.paused : GameState.State.playing);
            clearPauseVotes();
            showToast(voter, pausing);
            return true;
        }
        return false;
    }

    void refreshVotesAfterLeave() {
        if (voteTarget == null) return;

        pauseVotes.removeIf(id -> !isVotePlayerConnected(id));
        updateVoteProgress();
    }

    boolean isVotePlayerConnected(int id) {
        return Groups.player.getByID(id) != null || (net.server() && player != null && player.id == id);
    }

    int votePlayerCount() {
        int total = Groups.player.size();
        if (net.server() && player != null && Groups.player.getByID(player.id) == null) total++;
        return total;
    }

    void clearPauseVotes() {
        pauseVotes.clear();
        voteTarget = null;
    }

    void setPauseVoteState(GameState.State targetState) {
        applyingPauseVoteState = true;
        try {
            state.set(targetState);
        } finally {
            applyingPauseVoteState = false;
        }
    }

    void showVoteProgress(boolean pausing, int votes, int total) {
        if (net.server()) {
            Call.clientPacketReliable("multiplayerpause-votes", (pausing ? "p" : "u") + " " + votes + " " + total);
        }
        showVoteProgressLocally(pausing, votes, total);
    }

    void showVoteProgressLocally(boolean pausing, int votes, int total) {
        if (!Core.settings.getBool("multiplayerpause-toasts")) return;

        Menus.infoToast(Strings.format("@ votes: @/@ players", pausing ? "Pause" : "Unpause", votes, total), 2f);
    }

    void showToast(Player p, boolean paused) {
        if (net.server()) Call.clientPacketReliable("multiplayerpause-updatestate", (p == null ? -1 : p.id) + " " + (paused ? "t" : "f")); // Forward state change to clients
        if (!Core.settings.getBool("multiplayerpause-toasts")) return;

        String action = paused ? "paused" : "unpaused";
        String message = p == null
            ? Strings.format("The game was @.", action)
            : Strings.format("@ @ the game.", Strings.stripColors(p.name), action);
        Menus.infoToast(message, 2f);
    }
}