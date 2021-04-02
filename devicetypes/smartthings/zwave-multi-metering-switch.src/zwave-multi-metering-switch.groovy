/**
 *  Copyright 2018 SRPOL
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
    definition(
        name: "Z-Wave Multi Metering Switch",
        namespace: "smartthings",
        author: "SmartThings",
        mnmn: "SmartThings",
        vid: "generic-switch-power-energy",
        mcdSync: true,
        genericHandler: "Z-Wave"
    ) {
        capability "Switch"
        capability "Power Meter"
        capability "Energy Meter"
        capability "Refresh"
        capability "Configuration"
        capability "Actuator"
        capability "Sensor"
        capability "Health Check"

        command "reset"

        fingerprint mfr: "027A", prod: "A000", model: "A004", deviceJoinName: "Zooz Switch" //Zooz ZEN Power Strip
    }
}

def installed() {
    log.debug "Installed ${device.displayName}"
    sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
}

def updated() {
    sendHubCommand encap(zwave.multiChannelV3.multiChannelEndPointGet())
}

def configure() {
    log.debug "Configure..."
    response([
        encap(zwave.multiChannelV3.multiChannelEndPointGet()),
        encap(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
    ])
}

def parse(String description) {
    def result = null
    if (description.startsWith("Err")) {
        // FIXME this isn't right
        result = createEvent(descriptionText: description, isStateChange: true)
    } else if (description != "updated") {
        def cmd = zwave.parse(description)
        if (cmd) {
            result = zwaveEvent(cmd, null)
        }
    }
    // log.debug "parsed '${description}' to ${result.inspect()}"
    result
}

// cmd.endPoints includes the USB ports but we don't want to expose them as child devices since they cannot be controlled so hardcode to just include the outlets
def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelEndPointReport cmd, ep = null) {
    if (!childDevices) {
        addChildSwitches(5)
    }
    response([
        resetAll(),
        refreshAll()
    ])
}

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd, ep = null) {
    def mfr = Integer.toHexString(cmd.manufacturerId)
    def model = Integer.toHexString(cmd.productId)
    updateDataValue("mfr", mfr)
    updateDataValue("model", model)
    lateConfigure()
}

private lateConfigure() {
    def cmds = []
    log.debug "Late configuration..."
    // configure settings
    cmds = [
        encap(zwave.configurationV1.configurationSet(parameterNumber: 2, size: 4, scaledConfigurationValue: 5)),    // makes device report every 5W change
        encap(zwave.configurationV1.configurationSet(parameterNumber: 3, size: 4, scaledConfigurationValue: 600)), // enabling power Wattage reports every 10 minutes
        encap(zwave.configurationV1.configurationSet(parameterNumber: 4, size: 4, scaledConfigurationValue: 600))    // enabling kWh energy reports every 10 minutes
    ]
    sendHubCommand cmds
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd, ep = null) {
    log.debug "Security Message Encap ${cmd}"
    def encapsulatedCommand = cmd.encapsulatedCommand()
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand, null)
    } else {
        log.warn "Unable to extract encapsulated cmd from $cmd"
        createEvent(descriptionText: cmd.toString())
    }
}

def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd, ep = null) {
    // log.debug "Multichannel command ${cmd}" + (ep ? " from endpoint $ep" : "")
    if (cmd.commandClass == 0x6C && cmd.parameter.size >= 4) { // Supervision encapsulated Message
        // Supervision header is 4 bytes long, two bytes dropped here are the latter two bytes of the supervision header
        cmd.parameter = cmd.parameter.drop(2)
        // Updated Command Class/Command now with the remaining bytes
        cmd.commandClass = cmd.parameter[0]
        cmd.command = cmd.parameter[1]
        cmd.parameter = cmd.parameter.drop(2)
    }
    def encapsulatedCommand = cmd.encapsulatedCommand()
    zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd, ep = null) {
    log.debug "Basic ${cmd}" + (ep ? " from endpoint $ep" : "")
    handleSwitchReport(ep, cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, ep = null) {
    log.debug "Binary ${cmd}" + (ep ? " from endpoint $ep" : "")
    handleSwitchReport(ep, cmd)
}

private handleSwitchReport(endpoint, cmd) {
    def value = cmd.value ? "on" : "off"
    // device also sends reports without any endpoint specified, therefore all endpoints must be queried
    // sometimes it also reports 0.0 Wattage only until it's queried for it, then it starts reporting real values
    endpoint ? [changeSwitch(endpoint, value), response(encap(zwave.meterV3.meterGet(scale: 0), endpoint))] : [response(refreshAll(false))]
}

private changeSwitch(endpoint, value) {
    if (endpoint == 1) {
        createEvent(name: "switch", value: value, isStateChange: true, descriptionText: "Switch ${endpoint} is ${value}")
    } else {
        String childDni = "${device.deviceNetworkId}:${endpoint}"
        def child = childDevices.find { it.deviceNetworkId == childDni }
        child?.sendEvent(name: "switch", value: value, isStateChange: true, descriptionText: "Switch ${endpoint} is ${value}")
    }
}

def zwaveEvent(physicalgraph.zwave.commands.meterv3.MeterReport cmd, ep = null) {
    log.debug "Meter ${cmd}" + (ep ? " from endpoint $ep" : "")
    if (ep == 1) {
        // TODO: ep=1 should still be a child, not this parent
        return createEvent(createMeterEventMap(cmd))
    } else if (ep) {
        String childDni = "${device.deviceNetworkId}:$ep"
        def child = childDevices.find { it.deviceNetworkId == childDni }
        return child?.sendEvent(createMeterEventMap(cmd))
    } else {
        log.debug "Wattage change has been detected. Refreshing each endpoint"
        // return createEvent([isStateChange: false, descriptionText: "Wattage change has been detected. Refreshing each endpoint"])
        return null
    }
}

private createMeterEventMap(cmd) {
    def eventMap = [:]
    if (cmd.meterType == 1) {
        if (cmd.scale == 0) {
            eventMap.name = "energy"
            eventMap.value = cmd.scaledMeterValue
            eventMap.unit = "kWh"
        } else if (cmd.scale == 2) {
            eventMap.name = "power"
            eventMap.value = Math.round(cmd.scaledMeterValue)
            eventMap.unit = "W"
        }
    }
    eventMap
}

// This method handles unexpected commands
def zwaveEvent(physicalgraph.zwave.Command cmd, ep) {
    // Handles all Z-Wave commands we aren't interested in
    log.warn "${device.displayName} - Unhandled ${cmd}" + (ep ? " from endpoint $ep" : "")
}

def on() {
    onOffCmd(0xFF)
}

def off() {
    onOffCmd(0x00)
}

// The Health Check capability uses the “checkInterval” attribute to determine the maximum number of seconds the device can go without generating new events.
// If the device hasn’t created any events within that amount of time, SmartThings executes the “ping()” command.
// If ping() does not generate any events, SmartThings marks the device as offline.
def ping() {
    refresh()
}

def childOnOff(deviceNetworkId, value) {
    def switchId = getSwitchId(deviceNetworkId)
    if (switchId != null) sendHubCommand onOffCmd(value, switchId)
}

def childOn(deviceNetworkId) {
    childOnOff(deviceNetworkId, 0xFF)
}

def childOff(deviceNetworkId) {
    childOnOff(deviceNetworkId, 0x00)
}

private onOffCmd(value, endpoint = 1) {
    def cmds = []

    cmds += encap(zwave.basicV1.basicSet(value: value), endpoint)
    cmds += encap(zwave.basicV1.basicGet(), endpoint)

    cmds += "delay 3000"
    cmds += encap(zwave.meterV3.meterGet(scale: 0), endpoint)
    cmds += encap(zwave.meterV3.meterGet(scale: 2), endpoint)

    delayBetween(cmds)
}

private refreshAll(includeMeterGet = true) {
    def endpoints = [1]
    childDevices.each {
        def switchId = getSwitchId(it.deviceNetworkId)
        if (switchId != null) {
            endpoints << switchId
        }
    }
    sendHubCommand refresh(endpoints, includeMeterGet)
}

def childRefresh(deviceNetworkId, includeMeterGet = true) {
    def switchId = getSwitchId(deviceNetworkId)
    if (switchId != null) {
        sendHubCommand refresh([switchId], includeMeterGet)
    }
}

def refresh(endpoints = [1], includeMeterGet = true) {
    def cmds = []
    endpoints.each {
        cmds << [encap(zwave.basicV1.basicGet(), it)]
        if (includeMeterGet) {
            cmds << encap(zwave.meterV3.meterGet(scale: 0), it)
            cmds << encap(zwave.meterV3.meterGet(scale: 2), it)
        }
    }
    delayBetween(cmds, 200)
}

private resetAll() {
    childDevices.each { childReset(it.deviceNetworkId) }
    sendHubCommand reset()
}

def childReset(deviceNetworkId) {
    def switchId = getSwitchId(deviceNetworkId)
    if (switchId != null) {
        log.debug "Child reset switchId: ${switchId}"
        sendHubCommand reset(switchId)
    }
}

def reset(endpoint = 1) {
    log.debug "Resetting endpoint: ${endpoint}"
    delayBetween([
        encap(zwave.meterV3.meterReset(), endpoint),
        encap(zwave.meterV3.meterGet(scale: 0), endpoint),
        "delay 500"
    ], 500)
}

def getSwitchId(deviceNetworkId) {
    def split = deviceNetworkId?.split(":")
    return (split.length > 1) ? split[1] as Integer : null
}

private encap(cmd, endpoint = null) {
    if (cmd) {
        if (endpoint) {
            cmd = zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint: endpoint).encapsulate(cmd)
        }

        if (zwaveInfo.zw.contains("s")) {
            zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
        } else {
            cmd.format()
        }
    }
}

private addChildSwitches(numberOfSwitches) {
    log.debug "${device.displayName} - Executing addChildSwitches()"
    // TODO:
    //  1. main device represents the whole, nothing else (outlet 1 is a child)
    //  2. child devices added as components
    //  2. child devices for all 5 outlets (not just 2-5)
    //  3. USB ports
    for (def endpoint : 2..numberOfSwitches) {
        try {
            String childDni = "${device.deviceNetworkId}:$endpoint"
            def componentLabel = device.displayName[0..-2] + "${endpoint}"
            def childDthName = "Child Metering Switch"
            addChildDevice(childDthName, childDni, device.getHub().getId(), [
                completedSetup: true,
                label         : componentLabel,
                isComponent   : false
            ])
        } catch (Exception e) {
            log.debug "Exception: ${e}"
        }
    }
}

private static deviceIncludesMeter() {
    return true
}
