# Mindustry Campaign hosting is lacking
Alright to say the least there is no option to share or even see the tech tree & map which inturn makes campaign & multiplayer not worth the effort, unless Hosting a server.
And even hosting a Dedicated server is rather limited, Anuken unfortunately does not see this as fair but who said we needed progress for the clients? 

Well look no more this mod intends to expand campaign multiplayer gameplay without breaking other mod compatibility

## What is offered in this mod Currently
- Campaign Pausing, Including all clients if enabled
- Dedicated server Pausing, Not required in v8 anymore but there just incase its needed
- Save Sync (External Tool!)

## Whats Planned for later
- A Simple but dedicated menu with Client controls & Host setting for the host.
- Seperate Tech trees with a base tree OR Shared tech tree's for EACH player then collectively CONTRIBUTE to the overall tech tree (aiming for a "host" tree which is the starting tech for each player, This Sets the Entry tech. Though 4 modes are planned already)
- Gameplay Options, Maps will be considered to be dedicated servers! Meaning if you intend to host there will be a basic anti-cheat put in place so each player can launch where-ever if not already owned.
- Each player will have a Relations UI to manage Trade and Territory, Treaties and such. making RTS Have some new mechanics that require some thought!
- Unit Cap will be (UNIT_CAP * PlayerCap / AVERAGE_UNIT_COUNT) This way there are some dynamic's about unit control (Will touch up on unit control when implemented)
- Save Syncing, This one is tricky with just Mindustry so an external tool is used currently (See below)


## How to syncronize my save file with friends?
Its a bit tricky to learn at first, but if you intend to host and play campaign then using this can be usefull!

1. Download from the official Website or Repo
-REPO: https://github.com/syncthing/
-Website: https://syncthing.net/downloads/
2. Once downloaded follow this guide: https://docs.syncthing.net/intro/getting-started.html

Now the Eternal debate (step 3 for host and Clients):
Option 1. As the HOST start to share your Sector save folder itself (Export your save first as a backup!), Make sure its set to Send Only when sharing and hosting! If you want others to host as well set the share setting to Send & Recieve!

Option 2. As the SAVE OWNER Make a new folder to share and Export your save there, with Share set as Send & Recieve this means anyone else can host but not you!
Option 2 for Clients ONLY. As the CLIENT you can set your shared folder to Recieve only, this will make sure you retain YOUR tech tree when the host is playing alone
