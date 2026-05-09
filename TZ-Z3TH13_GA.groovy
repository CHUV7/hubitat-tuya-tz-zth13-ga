/**
 *  Tuya/Wing TS0201 Zigbee Temperature & Humidity Sensor Driver
 *  Model: ZTH13-3.0 Temp & Humidity Sensor
 *  TZ-Z3TH13_GA
 *  For Hubitat Elevation
 *
 *  Clusters used:
 *    0x0000 - Basic
 *    0x0001 - Power Configuration (battery)
 *    0x0402 - Temperature Measurement
 *    0x0405 - Relative Humidity Measurement
 *
 *  GitHub: https://github.com/CHUV7/hubitat-tuya-tz-zth13-ga/tree/main
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"
// How many seconds of silence before marking offline.
// Device max reporting interval is 300s, so 600s gives 2 missed cycles.
@Field static final int OFFLINE_TIMEOUT_SECS = 600

metadata {
    definition(
        name: "Tuya TS0201 Temp/Humidity Sensor with LCD",
        namespace: "CHUV7",
        author: "CHUV7",
        importUrl: "https://raw.githubusercontent.com/CHUV7/hubitat-tuya-tz-zth13-ga/refs/heads/main/TZ-Z3TH13_GA.groovy"
    ) {
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Sensor"
        capability "Health Check"

       attribute "healthStatus", "enum", ["online", "offline", "unknown"]

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0003,0001,0402,0405,0000",
            outClusters: "0003,0019,000A",
            manufacturer: "Wing", model: "TS0201",
            deviceJoinName: "Wing TS0201 Temp/Humidity Sensor"
    }

    preferences {
        input name: "tempOffset",      type: "decimal", title: "<b>Temperature Offset</b><br>Adjust reported temperature by this amount (°C)", defaultValue: 0.0, range: "-10..10"
        input name: "humOffset",       type: "decimal", title: "<b>Humidity Offset</b><br>Adjust reported humidity by this amount (%)",        defaultValue: 0.0, range: "-20..20"
        input name: "offlineTimeout",  type: "number",  title: "<b>Offline Timeout (seconds)</b><br>Mark sensor offline after this many seconds with no message (min 300)", defaultValue: OFFLINE_TIMEOUT_SECS, range: "300..86400"
        input name: "logEnable",       type: "bool",    title: "<b>Enable Debug Logging</b><br>Automatically disables after 30 minutes",       defaultValue: false
        input name: "txtEnable",       type: "bool",    title: "<b>Enable Description Text Logging</b>",                                       defaultValue: true
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName} installed (v${VERSION})"
    sendEvent(name: "healthStatus", value: "unknown")
    initialize()
}

def updated() {
    log.info "${device.displayName} preferences updated"
    if (logEnable) runIn(1800, disableDebugLog)
    else unschedule(disableDebugLog)
    initialize()
}

def initialize() {
    def cmds = []
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8,  30, 3600, 1)
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8,  30, 3600, 1)
    cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16,  10, 300,  10)
    cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, 10, 300,  50)
    sendZigbeeCommands(cmds)
    refresh()
    resetOfflineTimer()
}

def configure() {
    log.info "${device.displayName} configure()"
    initialize()
}

// ── Commands ─────────────────────────────────────────────────────────────────

def ping() {
    if (logEnable) log.debug "${device.displayName} ping()"
    refresh()
}

def refresh() {
    if (logEnable) log.debug "${device.displayName} refresh()"
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0020)
    cmds += zigbee.readAttribute(0x0001, 0x0021)
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += zigbee.readAttribute(0x0405, 0x0000)
    sendZigbeeCommands(cmds)
}

// Called by the scheduler when the offline timeout elapses
def markOffline() {
    if (device.currentValue("healthStatus") != "offline") {
        log.warn "${device.displayName} has not reported for ${offlineTimeout ?: OFFLINE_TIMEOUT_SECS}s — marking offline"
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
    }
}

// ── Parse ────────────────────────────────────────────────────────────────────

def parse(String description) {
    if (description?.startsWith("catchall")) {
        if (logEnable) log.debug "${device.displayName} catchall (ignored): ${description}"
        return
    }

    if (logEnable) log.debug "${device.displayName} parse() description = ${description}"

    def descMap = zigbee.parseDescriptionAsMap(description)
    if (logEnable) log.debug "${device.displayName} descMap = ${descMap}"

    if (descMap == null || descMap.isEmpty() || descMap.value == null) return

    // Any valid message = device is alive
    markOnline()

    switch (descMap.clusterInt) {
        case 0x0001: parseBattery(descMap);     break
        case 0x0402: parseTemperature(descMap); break
        case 0x0405: parseHumidity(descMap);    break
        default:
            if (logEnable) log.debug "${device.displayName} unhandled cluster: ${descMap.cluster}"
    }
}

// ── Cluster parsers ──────────────────────────────────────────────────────────

private void parseTemperature(Map descMap) {
    def raw = Integer.parseInt(descMap.value, 16)
    if (raw > 32767) raw -= 65536
    def rawC = raw / 100.0
    def adjC = Math.round((rawC + (tempOffset ?: 0.0)) * 10) / 10.0
    def tempDisplay = location.temperatureScale == "F"
        ? Math.round(celsiusToFahrenheit(adjC) * 10) / 10.0
        : adjC
    def unit = "°${location.temperatureScale}"
    updateDataValue("lastTemperatureRaw", "${rawC} °C")
    if (tempOffset) updateDataValue("lastTemperatureOffset", "${tempOffset} °C applied")
    if (txtEnable) log.info "${device.displayName} temperature is ${tempDisplay} ${unit}"
    sendEvent(name: "temperature", value: tempDisplay, unit: unit)
}

private void parseHumidity(Map descMap) {
    def raw = Integer.parseInt(descMap.value, 16)
    def rawPct = raw / 100.0
    def adjPct = Math.round((rawPct + (humOffset ?: 0.0)) * 10) / 10.0
    adjPct = Math.max(0.0, Math.min(100.0, adjPct))
    updateDataValue("lastHumidityRaw", "${rawPct} %")
    if (humOffset) updateDataValue("lastHumidityOffset", "${humOffset} % applied")
    if (txtEnable) log.info "${device.displayName} humidity is ${adjPct} % RH"
    sendEvent(name: "humidity", value: adjPct, unit: "% RH")
}

private void parseBattery(Map descMap) {
    def attrId = descMap.attrInt
    def raw    = Integer.parseInt(descMap.value, 16)

    if (attrId == 0x0021) {
        def pct = Math.max(0, Math.min(100, (int)(raw / 2)))
        updateDataValue("lastBatteryRaw", "0x${descMap.value} → ${pct}%")
        if (txtEnable) log.info "${device.displayName} battery is ${pct} %"
        sendEvent(name: "battery", value: pct, unit: "%")
    } else if (attrId == 0x0020) {
        def volts = raw / 10.0
        updateDataValue("lastBatteryVoltage", "${volts} V")
        if (logEnable) log.debug "${device.displayName} battery voltage = ${volts} V"
        if (device.currentValue("battery") == null) {
            def pct = Math.max(0, Math.min(100, (int)(((volts - 2.1) / (3.0 - 2.1)) * 100)))
            if (txtEnable) log.info "${device.displayName} battery (from voltage) is ${pct} % @ ${volts} V"
            sendEvent(name: "battery", value: pct, unit: "%")
        }
    }
}

// ── Health Check ─────────────────────────────────────────────────────────────

private void markOnline() {
    if (device.currentValue("healthStatus") != "online") {
        if (txtEnable) log.info "${device.displayName} is online"
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    resetOfflineTimer()
}

private void resetOfflineTimer() {
    def timeout = (offlineTimeout ?: OFFLINE_TIMEOUT_SECS) as int
    unschedule(markOffline)
    runIn(timeout, markOffline)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    if (logEnable) log.trace "${device.displayName} sendZigbeeCommands(${cmds})"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def disableDebugLog() {
    log.info "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
