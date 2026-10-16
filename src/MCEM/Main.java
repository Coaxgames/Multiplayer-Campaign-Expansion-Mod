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

    // Keep one vote per player ID; separate sets stop pause votes counting toward unpause (and vice versa).
    private final Set<Integer> pauseVotes = new HashSet<>();
    private final Set<Integer> unpauseVotes = new HashSet<>();
    // These flags let the state-change listener tell vote-driven changes from the host's normal pause key.
    private boolean applyingPauseVote;
    private boolean revertingPauseChange;

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

    // Add the settings players and hosts use to control pause notifications and permissions.
    void addSettings() {
        ui.settings.addCategory("Multiplayer Pause", Icon.pause, s -> {
            s.checkPref("multiplayerpause-toasts", true);
            s.checkPref("multiplayerpause-allowany", true);
            s.checkPref("multiplayerpause-adminsobeysync", false);
            s.checkPref("multiplayerpause-synconpause", false);
            s.checkPref("multiplayerpause-synconunpause", false);
            s.checkPref("multiplayerpause-schedulesync", false);
        });
    }

    // Run per-frame build-assist work and send client pause-key requests to the host.
    void setupEvents() {
        Events.run(Trigger.update, () -> {
            updatePauseVotes();
            ensureBuildAssistHint(); //Tis for BP Sharing
            if (state.isGame() && player != null && player.isBuilder() && !scene.hasDialog() && !scene.hasKeyboard() &&
                Core.input.keyTap(buildAssistKey)) {
                toggleBuildAssist();
            }
            updateBuildAssist();

            //Client Request to Pause --> Server/Host
            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() &&
                !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active() &&
                (state.isPaused() || state.isPlaying())) {
                if (net.client()) {
                    Call.serverPacketReliable("multiplayerpause-request", "");
                }
            }
        });
    }

    // Put the build-assist hint beside Mindustry's build instructions, or show a fallback button.
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

    // Show a small build-assist toggle when the normal hints are unavailable.
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

    // Remove the fallback toggle when the regular hint can be shown instead.
    void removeBuildAssistFallback() {
        if (buildAssistFallback == null) return;
        if (buildAssistFallback.parent != null) buildAssistFallback.parent.removeChild(buildAssistFallback);
        buildAssistFallback = null;
    }

    // Check whether a label is one of Mindustry's build-assist instruction labels.
    boolean isBuildInstructionLabel(Label label) {
        String text = label.getText().toString();
        return text.contains(Core.bundle.format("pausebuilding", Binding.pauseBuilding.value.key.toString())) ||
            text.contains(Core.bundle.format("resumebuilding", Binding.pauseBuilding.value.key.toString())) ||
            text.contains(Core.bundle.format("enablebuilding", Binding.pauseBuilding.value.key.toString()));
    }

    // Text changes based on whether build assist is currently enabled.
    String buildAssistHintText() {
        boolean enabled = Core.settings.getBool(buildAssistSetting, false);
        String hintKey = enabled ? "multiplayerpause-buildassist.hint-off" : "multiplayerpause-buildassist.hint-on";
        return Core.bundle.format(hintKey, buildAssistKey.value.key.getName());
    }

    // Flip the saved build-assist setting and notify the local player.
    void toggleBuildAssist() {
        boolean enabled = !Core.settings.getBool(buildAssistSetting, false);
        Core.settings.put(buildAssistSetting, enabled);
        Menus.infoToast(Core.bundle.get("multiplayerpause-buildassist." + (enabled ? "enabled" : "disabled")), 2f);
    }

    // Copy nearby teammates' build plans into this builder's queue while build assist is active.
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

    // Avoid adding a teammate's plan if this builder already has a plan at the same tile.
    boolean hasPlanAt(Unit builder, BuildPlan candidate) {
        for (BuildPlan plan : builder.plans()) {
            if (plan.x == candidate.x && plan.y == candidate.y) return true;
        }
        return false;
    }

    // Clients send pause requests here; state updates are sent back after a vote passes.
    void setupPackets() {
        //Server Sided; PacketHandler For Client Requests to pause with P Value
        netServer.addPacketHandler("multiplayerpause-request", (p, data) -> {
            handlePauseRequest(p, !state.isPaused());
        });
        // State changes are forwarded to clients for more responsive pausing (avoids waiting for next stateSnapshot) which should reduce desync (I hope) and allows for toasts
        netClient.addPacketHandler("multiplayerpause-updatestate", data -> { //this Updates the Clients Pause state
            String[] d = data.split(" ");
            if (d.length != 2) return;
            boolean paused = d[1].equals("t");
            state.set(paused ? GameState.State.paused : GameState.State.playing); // Reflect state change on the client ASAP
            showToast(Groups.player.getByID(Strings.parseInt(d[0])), paused);
        });
        //the event that actually tracks and pushes changes to clients; basicly pause logic is here
        // The host pause key changes state locally first, so turn that change into a vote unless it passes.
        Events.on(StateChangeEvent.class, event -> {
            if (!net.server() || !net.active()) return;
            if (applyingPauseVote) {
                clearPauseVotes();
                return;
            }
            if (revertingPauseChange) return;

            if ((event.from != GameState.State.paused && event.from != GameState.State.playing) ||
                (event.to != GameState.State.paused && event.to != GameState.State.playing)) {
                clearPauseVotes();
                return;
            }

            if (!Core.input.keyTap(Binding.pause)) {
                clearPauseVotes();
                return;
            }

            boolean targetPaused = event.to == GameState.State.paused;
            if (!canVote(player)) {
                revertPauseChange(event.from);
                return;
            }

            addPauseVote(player, targetPaused);
            if (hasUnanimousVote(targetPaused)) {
                clearPauseVotes();
                showToast(player, targetPaused);
            } else {
                revertPauseChange(event.from);
            }
        });
    }

    // Remove disconnected players' votes and pass a vote if the remaining connected players are unanimous.
    void updatePauseVotes() {
        if (!net.server() || !net.active()) return;
        if (!state.isGame() || state.rules.pauseDisabled) {
            clearPauseVotes();
            return;
        }

        Set<Integer> connectedIds = new HashSet<>();
        for (Player connected : Groups.player) connectedIds.add(connected.id);
        pauseVotes.retainAll(connectedIds);
        unpauseVotes.retainAll(connectedIds);

        if (!connectedIds.isEmpty() && !state.isPaused() && pauseVotes.size() == connectedIds.size()) {
            applyPauseVote(null, true);
        } else if (!connectedIds.isEmpty() && state.isPaused() && unpauseVotes.size() == connectedIds.size()) {
            applyPauseVote(null, false);
        }
    }

    // Check whether the player and current game state can accept a vote.
    boolean canVote(Player p) {
        return p != null && !state.isMenu() && !state.rules.pauseDisabled;
    }

    // A player's latest vote replaces their vote for the opposite action.
    void addPauseVote(Player p, boolean targetPaused) {
        if (targetPaused) {
            unpauseVotes.remove(p.id);
            pauseVotes.add(p.id);
        } else {
            pauseVotes.remove(p.id);
            unpauseVotes.add(p.id);
        }
    }

    // Count only connected players, so a departed player's old vote cannot block or pass the vote.
    boolean hasUnanimousVote(boolean targetPaused) {
        Set<Integer> connectedIds = new HashSet<>();
        for (Player connected : Groups.player) connectedIds.add(connected.id);
        pauseVotes.retainAll(connectedIds);
        unpauseVotes.retainAll(connectedIds);
        Set<Integer> votes = targetPaused ? pauseVotes : unpauseVotes;
        return !connectedIds.isEmpty() && votes.size() == connectedIds.size();
    }

    // Apply a passed vote, clear old votes, then sync the new state and toast to clients.
    void applyPauseVote(Player voter, boolean targetPaused) {
        clearPauseVotes();
        applyingPauseVote = true;
        state.set(targetPaused ? GameState.State.paused : GameState.State.playing);
        applyingPauseVote = false;
        showToast(voter, targetPaused);
    }

    // Undo the host's immediate pause-key state change while waiting for everyone to vote.
    void revertPauseChange(GameState.State previousState) {
        revertingPauseChange = true;
        state.set(previousState);
        revertingPauseChange = false;
    }

    // Clear both vote sets after a state change or when votes should no longer carry over.
    void clearPauseVotes() {
        pauseVotes.clear();
        unpauseVotes.clear();
    }

    //Controls Weather or not to pause based on settings THIS IS WHERE IM SETTING PAUSE STATE. NETWORKING JUST SYNCS IT
    // Apply the existing client permission check, record the vote, and change state only once everyone agrees.
    void handlePauseRequest(Player p, boolean targetPaused) {
        if (!net.server() || !canVote(p) ||
            (!p.admin && !Core.settings.getBool("multiplayerpause-allowany")) ||
            targetPaused == state.isPaused()) return;

        addPauseVote(p, targetPaused);
        if (hasUnanimousVote(targetPaused)) applyPauseVote(p, targetPaused);
    }

    // Tell clients about the pause state and show a local toast if toasts are enabled.
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