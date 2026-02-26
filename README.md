# Hue MC Controller

Control Philips Hue lights from a Paper/Spigot server. Configure the Hue bridge, toggle all lights, and bind levers to single lights or a captured scene.

## Features
- Bridge pairing: `/bridge set <ip>` with guided link-button prompt
- Global power: `/lights on|off`
- List lights: `/list lights`
- Lever bindings:
  - `/set light <name>` then right-click a lever to bind it to that light
  - `/set scene` captures current states for all lights; right-click a lever to bind playback/off toggle

## Requirements
- Java 21
- Paper API 1.21.1 (provided scope)
- Hue bridge on the same network

## Build
```bash
mvn clean package
```
Outputs `target/hue-mc-controller-1.0-SNAPSHOT-shaded.jar`.

## Install
1. Place the shaded jar in your server `plugins/` folder.
2. Start the server once to generate `config.yml`.

## Commands
- `/bridge set <ip>` — set bridge IP and start pairing (press link button)
- `/lights <on|off>` — toggle all lights (group 0)
- `/list lights` — list light names from the bridge
- `/set light <name>` — select a light by name/substring, then right-click a lever within 60s to bind
- `/set scene` — snapshot all light states, then right-click a lever within 60s to bind; lever on restores snapshot, lever off powers lights off

## Binding behavior
- Bindings persist in `config.yml` under `bindings`.
- Lever ON triggers Hue action; lever OFF turns the bound light off or applies the off state for a scene.

## Notes
- The plugin works asynchronously for Hue calls and switches to the main thread only to send chat messages.
- If you change the bridge IP, the stored API key is cleared and you must pair again.
