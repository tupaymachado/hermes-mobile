package com.m57.hermescontrol.data.ws

/**
 * Change-event vocabulary for [WsEvent.ChangeEvent].
 *
 * The gateway advertises `change_events: true` in the `gateway.ready`
 * handshake and broadcasts these when its on-disk signatures move
 * (`tui_gateway/server.py` `_CHANGE_WATCHES`), so clients can demote
 * blind polling to a slow backstop. Backends without the feature never
 * broadcast — consumers simply stay quiet.
 *
 * `pet.changed` is deliberately absent: mobile has no pet feature, so it
 * is neither parsed nor consumed.
 */
object ChangeEvents {
    const val CRON = "cron.changed"

    /**
     * A profile's gateway went up or down. Feeds the Bot Mode roster's
     * presence column (`ProfileInfo.gateway_running`). Gateways that do not
     * watch this signature simply never broadcast it, so consumers degrade to
     * refreshing on [SESSIONS] alone — never to a stale-forever roster, since
     * pull-to-refresh and re-entering the screen still reload.
     */
    const val GATEWAY = "gateway.changed"
    const val SESSIONS = "sessions.changed"
    const val PLATFORMS = "platforms.changed"
    const val PAIRING = "pairing.changed"
}
