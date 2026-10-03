# Project X-BAT / carrier — VTOL Build Manual

A scaled-up X-Bat-style tailless VTOL, built in **Create: Aeronautics** and flown by a **CC:Tweaked** flight computer. Vertical thrust for hover/landing, jet thrust for cruise, an internal bay for a deployable mining rig — one toggle between hover and jet, autopilot bolted on later.

| Field | Value |
|---|---|
| Physics engine | Create: Aeronautics (Sable) |
| Brain | CC:Tweaked |
| MC target | **1.21.1 NeoForge** |
| Modes | VTOL · JET |
| Status | Planning |

---

## SEC 01 — Pre-flight reality check

Everything described is buildable, and the hard part (a computer driving thrusters) is off-the-shelf. Three constraints decide whether the pack even launches:

- **MASTER CAUTION · Version lock.** Create: Aeronautics + addons are in active beta and each pins ONE Minecraft + ONE Create version. Pick **1.21.1 NeoForge** and reject any mod without a build for it. A 1.20.1-only mod is *excluded*, not "add later."
- **MASTER CAUTION · One physics engine.** A single aircraft runs on one engine. Create: Aeronautics (Sable) and Valkyrien Skies 2 are mutually exclusive for the same contraption. **Decision: Aeronautics/Sable only. No Valkyrien Skies.**
- **CAUTION · The internal lift is the risky part.** A powered elevator moving a vehicle up/down *inside* a hovering physics contraption is the single most finicky thing in the spec. Lazy/robust version: the mining rig is its OWN small contraption that drives/drops out a bay door. Powered internal lift = stretch goal only.

---

## SEC 02 — The mod stack

Status flags: **[OK]** confirmed to exist & fit · **[CHK]** matches a real project, verify name/version on Modrinth/CurseForge before adding.

### 2.1 Physics core & flight (non-negotiable)
| Mod | Role | Status |
|---|---|---|
| Create: Aeronautics (Sable) | Assembles Create contraptions into simulated physics bodies that fly | OK |
| Create: Propulsion — Simulated | Propulsion-Team physics/assembly core the family builds on ("create propulsion simulated") | OK |
| Create Aeronautics: Thrusters & Things | Adds thrusters + control blocks — VTOL lift jets & rear cruise engines | OK |
| Create Aeronautics: Gadgets & Gizmos | Thrusters + **CC:Tweaked peripheral support** (thrusters, bearings, gearbox, joystick, controllers); ships in-game Lua docs | OK |

### 2.2 The brain: control & input
| Mod | Role | Status |
|---|---|---|
| CC:Tweaked | The flight computer — runs Lua stabilization + mode toggle + autopilot | OK |
| Create: Avionics | "ComputerCraft for Aeronautics" — CC peripherals for attitude, altitude, velocity, bearing; drive throttle & propellers. Primary sensor+actuator bridge | OK |
| Drive-By-Wire (Sable network) | Wire/signal fabric carrying control channels around the airframe | OK |
| Drive-By-Wire: Typewriter | Keyboard block — right-click, every keypress broadcasts as a wire channel (held = 1, released = 0). Your WASD/QE/toggle input source | OK |
| Create: Tweaked Controllers | Keyboard drives Create controllers directly — no-code fallback for manual flight | OK |
| Create: Connected | Better seats / contraption controls / multiplayer sync | OK |
| Create: Ender Transmission | Wireless rotational power — for bay doors / lift without a shaft through a moving hull | CHK |
| Create: Linked / Linked Thrusters | Group thrusters so one signal fires a whole set (all VTOL jets together) | CHK |

### 2.3 Weapons (if "strike" is real)
| Mod | Role | Status |
|---|---|---|
| Create Big Cannons (CBC) | Ballistic cannons that mount on contraptions | OK |
| CBC — mounts / compact mounts | Compact turret & elevation mounts | CHK |
| CBC — Advanced Technologies | Extended CBC munitions/tech | CHK |
| PointBlank | Configurable modern firearms (small arms, separate from CBC) | CHK |
| Create: Military / Military Supplement | Military-themed Create blocks & decor | CHK |

### 2.4 Building the shell & the X-Bat look
| Mod | Role | Status |
|---|---|---|
| Copycats+ | Most important cosmetic mod — copy any texture onto slabs/panels/angled shapes for smooth stealth-jet curves | OK |
| Framed Blocks | Framed slopes/corners you retexture — wing leading edges & chines | OK |
| Rolled Homogeneous Armour / Steel Armor Blocks | Armored plating aesthetic | CHK |
| Create Aeronautics: Toolgun | Selection/edit toolgun for assembling big contraptions faster | CHK |

### 2.5 Utility / economy / leftovers from the list
| Mod | Role | Status |
|---|---|---|
| The Factory Must Grow (TFMG) | Big Create industry expansion — build/fuel the fleet | CHK |
| Create: Radar / Radars | Detection/targeting readouts you can pipe into the HUD | CHK |
| Create Tracks / Aeronautics Offroad | Wheels/tracks for the mining rig & ground vehicles | CHK |

Unresolved from the list (likely small/Discord-hosted addons — confirm each exists for your MC version): *aeroworks, synaxis, vista aeronautics, deep sea framed, forked, SBW, aeronautics compact / lift patch, aeronautics tournament, simulated addition, linear motion, connected.*

### 2.6 Warp / "Star Wars" FTL — honest answer
| Mod | Role | Status |
|---|---|---|
| WarpDrive | Classic CC-integrated FTL/hyperspace (ship-jump blocks, computer-controllable). Closest to Star Wars warp — historically old-version, check for a build on your MC target | CHK |
| Ad Astra / space mods | If you want actual space/planet travel. Won't ride on the Aeronautics contraption | CHK |

**Reality:** there is no clean, current "Star Wars hyperdrive that snaps onto a Create: Aeronautics jet." Treat warp as a *separate* late-game system on its own block, not integrated into the flight model. Fly the VTOL first.

---

## SEC 03 — Engine decision (settled)

- **Physics engine = Create: Aeronautics (Sable). No Valkyrien Skies.** Sable IS the physics layer of Aeronautics — not redundant, not missing anything. VS+Clockwork is a competing engine with a different CC bridge (CC:VS) and no typewriter integration; kept only as a fallback if Aeronautics is too buggy.
- **Version + loader = Minecraft 1.21.1, NeoForge.** That's where the ecosystem is centered and where the Drive-By-Wire Typewriter is confirmed. Pin the whole pack to 1.21.1 NeoForge.

---

## SEC 04 — Airframe

Keep the X-Bat silhouette (tailless, blended body, forward chines) but upsize for internal volume. Real X-BAT reference = **4 VTOL lift fans around the CoM + 1 rear turbofan**; this build uses **dual rear thrusters**.

### Propulsion blocks (in-game item, not the mod)
Thrust-producing items in the Aeronautics family (confirm exact names in-game):

| Block | Use | Notes |
|---|---|---|
| **Thruster** (Thrusters & Things / Gadgets & Gizmos) | **Both** rear cruise + 4 VTOL lift | Directional jet thrust, throttle-controllable, CC-addressable. The workhorse. Same block, different orientation. |
| Propellers (core Aeronautics) | Cruise if you want turboprop feel | Needs a shaft + Create rotational power. Less stealth-jet. |
| Hot-air / balloon gas (core Aeronautics) | — | Buoyancy, not jet thrust. Not for an X-BAT. |

**Use Thrusters throughout.** The only thing making the rear pair "engines" vs the lift set is orientation + which mode drives them. Check in-game whether a Thruster wants fuel / rotational input or just a redstone/CC throttle — that decides if you also plumb Create power or a fuel line.

### Thruster layout (core mechanical decision)
- **Cruise (JET mode):** 2 thrusters at the tail, pointing aft. High output. In jet mode these do ~all the work.
- **Lift (VTOL mode):** 4 downward thrusters in a rectangle around the center of mass — two forward (chin/canard roots), two aft (wing roots). Can hide behind Copycats+ doors; VTOL thrust doesn't need to be strong, just balanced.
- **Why 4 in a rectangle:** the computer holds level attitude by trimming the four differentially (front pair up = pitch down, left pair up = roll right). Two thrusters can't do pitch AND roll. Four is the minimum for computer-stabilized hover.
- **Balance:** the four must be symmetric about the CoM or it tips on liftoff. Build the heavy internal bay centered; add trim ballast after the first hover test.

### Internal bay + mining rig
- Hollow the belly. A bottom bay door (Copycats+ trapdoor panels driven by a small bearing / Ender Transmission) beats a side ramp on a hovering body.
- **Do first:** mining rig is its own separate small contraption — hover low, open door, it drives/drops out. No moving lift inside the moving airframe.
- **Stretch:** powered internal lift platform — only after the aircraft flies reliably.

---

## SEC 05 — Control scheme

The typewriter turns each key into a wire channel; the computer reads channels and decides what thrust that means *in the current mode*. Same keys, different behavior per mode — that's the point of a computer in the loop.

| Key | Action | Behavior |
|---|---|---|
| W | Forward | JET: pitch/throttle ahead · VTOL: nudge forward |
| S | Back | JET: throttle down · VTOL: nudge back |
| A | Strafe left | VTOL: translate left · JET: roll or ignore |
| D | Strafe right | VTOL: translate right · JET: roll or ignore |
| Q | Yaw left | rotate nose left (both modes) |
| E | Yaw right | rotate nose right (both modes) |
| Space | Climb | VTOL: more lift · JET: pitch up |
| Shift | Descend | VTOL: less lift · JET: pitch down |
| **T** | **Mode toggle** | flip VTOL ⇄ JET (edge-triggered) |

The computer ALWAYS owns stabilization. Keys are *requests* ("I want to go forward"), not direct thruster commands. The stabilizer takes the request + current attitude and computes per-thruster output.

---

## SEC 06 — Flight computer (CC:Tweaked)

One computer, one loop, three jobs: read the airframe, read the pilot, write the thrusters. API method names below are **illustrative** — confirm against the Avionics / Gadgets in-game Lua docs at `/rom/thrusters/docs.lua`.

- **Sensors:** Avionics gauge peripheral → pitch, roll, yaw-rate, altitude, velocity.
- **Input:** Drive-By-Wire Typewriter → wire channels → computer reads which keys are held.
- **Actuators:** thruster peripherals (or thruster groups) → set throttle 0–1 each.
- **Controller:** a tiny **PID per axis** for stabilization. The one piece of real logic; everything else is plumbing.

**PID needs tuning knobs.** Gains `Kp/Ki/Kd` depend on mass, thruster power, and CoM — no correct value in advance. Keep them as tunable constants at the top of the file and dial them in on the pad.

```lua
-- flight.lua  ·  X-BAT stabilized VTOL/JET controller
-- API names illustrative: verify against Avionics/Gadgets docs (/rom/thrusters/docs.lua)

-- === TUNING KNOBS (dial these in on the pad, per airframe) ===
local GAIN = {
  pitch = { p=0.8, i=0.02, d=0.3 },  -- start here, adjust after first hover
  roll  = { p=0.8, i=0.02, d=0.3 },
  yaw   = { p=0.6, i=0.00, d=0.1 },
}
local HOVER_THROTTLE = 0.5   -- base lift to roughly hold altitude
local DT = 0.05              -- loop period (1 tick)

-- === PERIPHERALS (rename to your actual sides/names) ===
local imu   = peripheral.wrap("back")    -- Avionics gauge: attitude/vel/alt
local lift  = { --[[ 4 VTOL thruster groups: fl, fr, rl, rr ]] }
local cruise= { --[[ 2 rear thrusters ]] }
local keys  = peripheral.wrap("left")    -- typewriter channel reader

-- minimal PID; one instance per axis
local function pid(g)
  local i, prev = 0, 0
  return function(err)
    i = i + err*DT
    local d = (err - prev)/DT; prev = err
    return g.p*err + g.i*i + g.d*d
  end
end
local pPitch, pRoll, pYaw = pid(GAIN.pitch), pid(GAIN.roll), pid(GAIN.yaw)

local mode = "VTOL"
local lastT = false

local function clamp(x) return math.max(0, math.min(1, x)) end

while true do
  local a  = imu.getAttitude()      -- {pitch,roll,yaw,...}
  local in_ = keys.held()           -- {w=,s=,a=,d=,q=,e=,space=,shift=,t=}

  -- edge-triggered mode toggle on T
  if in_.t and not lastT then mode = (mode=="VTOL") and "JET" or "VTOL" end
  lastT = in_.t

  -- stabilization: drive attitude error toward pilot's requested setpoint
  local setPitch = (in_.space and 10 or 0) - (in_.shift and 10 or 0)
  local cp = pPitch(setPitch - a.pitch)
  local cr = pRoll(0 - a.roll)          -- always fight roll to 0
  local cy = pYaw(((in_.e and 1 or 0)-(in_.q and 1 or 0)) - a.yawRate)

  if mode == "VTOL" then
    local base = HOVER_THROTTLE + (in_.space and 0.25 or 0) - (in_.shift and 0.25 or 0)
    -- differential: +pitch = more front lift, +roll = more left lift
    lift.fl.set(clamp(base + cp + cr))
    lift.fr.set(clamp(base + cp - cr))
    lift.rl.set(clamp(base - cp + cr))
    lift.rr.set(clamp(base - cp - cr))
    for _,t in ipairs(cruise) do t.set((in_.w and 0.15 or 0)) end -- gentle nudge
  else -- JET: rear thrust does the flying, attitude aerodynamic
    for _,t in ipairs(lift) do t.set(0) end
    local thr = (in_.w and 1 or 0.4) - (in_.s and 0.4 or 0)
    for _,t in ipairs(cruise) do t.set(clamp(thr)) end
    -- cp/cr/cy here drive control surfaces / bearing trim instead of lift jets
  end

  os.sleep(DT)
end
```

**Build note:** write this incrementally. Get `imu.getAttitude()` printing real numbers first. Then hover on ONLY the 4 lift thrusters with roll+pitch PID and no pilot input — tune until it sits level. Then add translation, yaw, JET mode, then the toggle. Each layer on a working one.

---

## SEC 07 — Autopilot (later)

Same loop, setpoints from a route instead of the typewriter. Don't build until hover + jet both work by hand.

- **Altitude hold:** a 4th PID on `altitude` that trims `HOVER_THROTTLE`. This alone is 80% of "feels autonomous."
- **Heading hold:** yaw PID targets a compass bearing instead of a key.
- **Waypoints:** store `{x,z,alt}` list; steer to next, JET when far, VTOL when arriving. Position via CC:Tweaked `gps` API (needs GPS host towers).
- **Failsafe (don't skip):** if attitude exceeds a limit or a peripheral drops, cut to safe hover / auto-descend. A runaway armed aircraft is a bad afternoon.

---

## SEC 08 — Build order

1. **Pack boots.** Lock MC 1.21.1 NeoForge + Create + Aeronautics core only. Confirm world loads and a trivial contraption assembles. Add nothing else.
2. **It hovers, dumb.** 4 lift thrusters + manual Tweaked Controllers, no computer. Prove it leaves the ground and roughly balances. Add trim ballast until it doesn't tip.
3. **Computer reads the world.** Add CC:Tweaked + Avionics. Get attitude/altitude printing on a monitor. No control yet.
4. **Computer holds hover.** PID loop drives the 4 lift thrusters to level, hands-off. Tune `GAIN`. Hardest milestone — budget time.
5. **Pilot input.** Add Drive-By-Wire Typewriter; map WASD/QE/Space/Shift to setpoints. Fly manually in VTOL.
6. **Jet mode + toggle.** Add 2 rear cruise thrusters and the T mode flip. Get forward flight working.
7. **The airframe.** NOW wrap it in Copycats+/Framed Blocks into the X-Bat shape. Skin last — reshaping earlier makes you re-tune balance.
8. **Bay + mining rig.** Belly door, separate rig drives out. Powered internal lift only if patience remains.
9. **Autopilot.** Altitude hold → heading hold → waypoints → failsafe.
10. **Weapons / warp / rest.** CBC, PointBlank, WarpDrive — bolt-ons after it's a real aircraft.

---

*Build manual v0.1 · engine: Create: Aeronautics (Sable) · brain: CC:Tweaked · MC 1.21.1 NeoForge*
*Status flags: OK = confirmed exists & fits · CHK = verify name/version on Modrinth/CurseForge before adding. API method names in SEC 06 are illustrative — confirm against in-game Lua docs.*
