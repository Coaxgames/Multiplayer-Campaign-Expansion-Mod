package MCEM;

import arc.*;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.*;
import arc.util.Log;
import mindustry.core.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.mod.*;
import mindustry.ui.*;

import static arc.Core.*;
import static mindustry.Vars.*;

public class Main extends Mod {
    private static final String buildAssistSetting = "multiplayerpause-buildassist";

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
            s.checkPref("multiplayerpause-synconunpause", false);
            s.checkPref("multiplayerpause-schedulesync", false);
        });
    }

    void setupEvents() {
        Events.run(Trigger.update, () -> {
            //For BP Sharing
            ensureBuildAssistHint();
            if (state.isGame() && player != null && player.isBuilder() && !scene.hasDialog() && !scene.hasKeyboard() &&
                Core.input.keyTap(buildAssistKey)) {
                toggleBuildAssist();
            }
            updateBuildAssist();

            //for pause logic
            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() &&
                !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active() &&
                (state.isPaused() || state.isPlaying())) {
                if (net.client()) {
                    Call.serverPacketReliable("multiplayerpause-request", "");
                }
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
        //PLayer to Local function below, i assume that is synced
        netServer.addPacketHandler("multiplayerpause-request", (p, data) -> {
            Log.info("DEBUG CLIENTCALLED PAUSETOGGLE: @", p, data, !state.isPaused());
            handlePauseRequest(p, !state.isPaused());//send request to flip state
        });
        // State changes are forwarded to clients for more responsive pausing (avoids waiting for next stateSnapshot) which should reduce desync (I hope) and allows for toasts
        netClient.addPacketHandler("multiplayerpause-updatestate", data -> {
            String[] d = data.split(" ");
            if (d.length != 2) return;
            boolean paused = d[1].equals("t");

            //check and set
            Log.info("DEBUG BEFORE: @", paused);
            state.set(paused ? GameState.State.paused : GameState.State.playing); // Reflect state change on the client ASAP

            //toast
            Log.info("DEBUG AFTER: @", paused);
            showToast(Groups.player.getByID(Strings.parseInt(d[0])), paused);
        });
        //works out if game is paused for each client or not and will update the client i think?
        Events.on(StateChangeEvent.class, event -> {
            if (!net.server() || !net.active() ||
                !Core.input.keyTap(Binding.pause) ||
                (event.from != GameState.State.paused && event.from != GameState.State.playing) ||
                (event.to != GameState.State.paused && event.to != GameState.State.playing) ||
                player == null) return;

            boolean targetPaused = event.to == GameState.State.paused;
            showToast(player, targetPaused);
        });
    }

    //Main Handle for pause logic
    void handlePauseRequest(Player p, boolean targetPaused) {
        if (p == null || !net.server() || state.isMenu() || state.rules.pauseDisabled ||
            (!p.admin && !Core.settings.getBool("multiplayerpause-allowany"))) return;

        Log.info("DEBUG OUT-BEFORE: @", targetPaused);
        //This sets pause state for others, turning this line off makes it so only the host can pause or unpause; The toasts still show up though
        state.set(targetPaused ? GameState.State.paused : GameState.State.playing);

        //Toast
        Log.info("DEBUG OUT-AFTER: @", targetPaused);
        showToast(p, targetPaused);
    }

    void showToast(Player p, boolean paused) {
        if (net.server()) Call.clientPacketReliable("multiplayerpause-updatestate", (p == null ? -1 : p.id) + " " + (paused ? "t" : "f")); // Forward state change to clients
        if (!Core.settings.getBool("multiplayerpause-toasts")) return;

        String action = paused ? "paused" : "unpaused";
        Log.info("TOAST OUT: @", paused);
        String message = p == null
            ? Strings.format("The game was @.", action)
            : Strings.format("@ @ the game.", Strings.stripColors(p.name), action);
        Menus.infoToast(message, 2f);
    }
}