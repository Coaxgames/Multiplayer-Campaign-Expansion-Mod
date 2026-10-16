package MCEM;

import arc.*;
import arc.util.*;
import mindustry.core.*;
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
    private final Set<Integer> pauseVotes = new HashSet<>();
    private Boolean voteTarget;

    @Override
    public void init() {
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
            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() && !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active()) {
                if (net.client()) Call.serverPacketReliable("multiplayerpause-request", ""); // Send pause request
                else {
                    boolean targetPaused = state.isPaused();
                    if (state.isCampaign() && state.afterGameOver || state.rules.pauseDisabled) return;

                    if (isSyncEnabled(targetPaused) && (!player.admin || Core.settings.getBool("multiplayerpause-adminsobeysync"))) {
                        boolean completed = castPauseVote(player, targetPaused);
                        if (!completed) state.set(targetPaused ? GameState.State.playing : GameState.State.paused);
                    } else {
                        clearPauseVotes();
                        showToast(player, targetPaused);
                    }
                }
            }
        });
    }

    void setupPackets() {
        netServer.addPacketHandler("multiplayerpause-request", (p, data) -> {
            if ((!p.admin && !Core.settings.getBool("multiplayerpause-allowany")) || state.isMenu() || state.rules.pauseDisabled) return;

            boolean targetPaused = !state.isPaused();
            if (p.admin && !Core.settings.getBool("multiplayerpause-adminsobeysync")) {
                clearPauseVotes();
                state.set(targetPaused ? GameState.State.paused : GameState.State.playing);
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
            state.set(targetPaused ? GameState.State.paused : GameState.State.playing);
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
            state.set(pausing ? GameState.State.paused : GameState.State.playing);
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