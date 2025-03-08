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
            //s.checkPref("Techtree-toasts", true);
            s.checkPref("normal-toasts", true);
            s.checkPref("mp-allclientscanpause", true);
            //s.checkPref("mp-SyncTechTreeToClients", true);
        });
    }

    void setupEvents() {
        //Events.on(UnlockEvent.class, c -> { //i need the name of the Unlocked content, This is passing the UnlockEvent to c. so i may need to get help on that as well
        //    //Add a check here to see if it was player 1, once i create the UI for clients to unlock stuff then ill add smth in to bypass sending back from the player that called this
        //    if (net.client()) Call.serverPacketReliable("Techtree-UnlockSync", c); // Send pause request
        //    else showTechToast(player, c); // Show toast for host pausing (inverted as the state hasn't been updated yet)
        //    
        //});
        Events.run(Trigger.update, () -> {
            if (Core.input.keyTap(Binding.pause) && !renderer.isCutscene() && !scene.hasDialog() && !scene.hasKeyboard() && !ui.restart.isShown() && state.isGame() && net.active()) {
                if (net.client()) Call.serverPacketReliable("multiplayerpause-request", ""); // Send pause request
                else showPause(player, !state.isPaused()); // Show toast for host pausing (inverted as the state hasn't been updated yet)
            }
        });
    }

    void setupPackets() {
        //Send request to server, So that the server can toast and sync to the other clients
        //netServer.addPacketHandler("Techtree-UnlockSync", (p, data) -> {
        //    if (Core.settings.getBool("mp-SyncTechTreeToClients")) return;
//
//
        //    //state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
        //    showTechToast(p, content);
        //});
        //Forward Unlock to clients as the server to the clients
        //netServer.addPacketHandler("Techtree-UnlockSync-updateclient", (p, data) -> {
        //    
        //    String[] d = data.split(" ");
        //    //Add the tech unlock thing here for all players (Though i need to find a way to avoid sending back to the host player, may even add the Tech API Before hand)
        //    showTechToast(Groups.player.getByID(Strings.parseInt(d[0])), d[1]);
        //});

        //Send request to server, So the clients can have the change seen on the host
        netServer.addPacketHandler("multiplayerpause-request-updatestate", (p, data) -> {
            if (!(p.admin || Core.settings.getBool("mp-allclientscanpause")) ||  state.isMenu()) return;


            state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
            showPause(p, state.isPaused());
        });
        //State changes are forwarded to clients for more responsive pausing, If any issues with de-sync then use Beforegameupdate on the api
        netClient.addPacketHandler("multiplayerpause-updatestate", data -> {
            String[] d = data.split(" ");
            if (d.length != 2) return;
            boolean paused = d[1].equals("t");
            state.set(paused ? GameState.State.paused : GameState.State.playing); // Reflect state change on the client ASAP
            showPause(Groups.player.getByID(Strings.parseInt(d[0])), paused);
        });
    }
    
    //Shows players who paused (if not the server)
    void showPause(Player p, boolean paused) {
        //Sync & Reset UI + Build plans
        if (net.server()) Call.clientPacketReliable("multiplayerpause-updatestate", p.id + " " + (paused ? "t" : "f"));//Forward change to players
        
        if (!Core.settings.getBool("normal-toasts")) return; //Push toast if enabled
        Menus.infoToast(Strings.format("@ @ the game.", p == null ? "[lightgray]Unknown player[]" : Strings.stripColors(p.name), paused ? "paused" : "unpaused"), 2f);
    }
    
    //Shows players That another player has unlocked content
    //void showTechToast(Player p, content t) {
    //    if (net.server()) Call.clientPacketReliable("Techtree-UnlockSync-updateclient", t);//Forward change to players
//
    //    if (!Core.settings.getBool("Techtree-toasts")) return; //Push toast if enabled
    //    Menus.infoToast(Strings.format("@ @ the game.", p == null ? "[lightgray]Unknown player[]" : Strings.stripColors(p.name), content), 2f);
    //}
}
