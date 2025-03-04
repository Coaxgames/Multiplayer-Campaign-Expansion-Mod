package MCEM;

import arc.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.mod.*;
import mindustry.ui.*;

import static arc.Core.*;
import static mindustry.Vars.*;

public class Main extends Mod {
    private long lastSyncTime;

    @Override
    public void init() {
        addSettings();
        setupEvents();
        setupPackets();
    }

    void addSettings() {
        ui.settings.addCategory("Multiplayer Pause", Icon.pause, s -> {
            s.checkPref("multiplayerpause-toasts", true);
            s.checkPref("multiplayerpause-syncon", false);
            s.checkPref("multiplayerpause-allowany", true);
            s.checkPref("multiplayerpause-schedulesync", false);
        });
    }

    void setupEvents() {
        Events.run(Trigger.update, () -> {
            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() && !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active()) {
                if (net.client()) Call.serverPacketReliable("multiplayerpause-request", ""); // Send pause request
                else showToast(player, !state.isPaused()); // Show toast for host pausing (inverted as the state hasn't been updated yet)
            }
        });
    }

    void setupPackets() {
        netServer.addPacketHandler("multiplayerpause-request", (p, data) -> {
            if (!(p.admin || Core.settings.getBool("multiplayerpause-allowany")) ||  state.isMenu()) return;


            state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
            showToast(p, state.isPaused());
        });
        // State changes are forwarded to clients for more responsive pausing (avoids waiting for next stateSnapshot) which should reduce desync (I hope) and allows for toasts
        netClient.addPacketHandler("multiplayerpause-updatestate", data -> {
            String[] d = data.split(" ");
            if (d.length != 2) return;
            boolean paused = d[1].equals("t");
            state.set(paused ? GameState.State.paused : GameState.State.playing); // Reflect state change on the client ASAP
            showToast(Groups.player.getByID(Strings.parseInt(d[0])), paused);
        });
    }
    
    //Shows players who paused (if not the server)
    void showPause(Player p, boolean paused, String msg) {
        //Sync & Reset UI + Build plans
        if (net.server()) Call.clientPacketReliable("multiplayerpause-updatestate", p.id + " " + (paused ? "t" : "f"));//Forward change to players
        
        if (!Core.settings.getBool("multiplayerpause-toasts")) return; //Push toast if enabled
        Menus.infoToast(Strings.format("@ @ the game.", p == null ? "[lightgray]Unknown player[]" : Strings.stripColors(p.name), paused ? "paused" : "unpaused"), 2f);
    }
    
    //A toast Function for General Notifactions With Targeted Clients (To target all use: Groups.player.getByID(Strings.parseInt(d[0])))
    void showToast(Player p, String msg) {
        if (!Core.settings.getBool("multiplayerpause-toasts")) return; //Push toast if enabled
        Menus.infoToast(Strings.format("@ @ the game.", p == null ? msg : Strings.stripColors(p.name), msg), 2f);
    }
}
