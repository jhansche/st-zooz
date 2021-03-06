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
        namespace: "chapterdream03931",
        author: "SmartThings/jhansche",
        mnmn: "SmartThingsCommunity",
        vid: "4f13280b-548e-3c0f-99ab-ab717a9b5851",
        mcdSync: true,
        genericHandler: "Z-Wave"
    ) {
        // capability "Switch" // for all-on, all-off??
        capability "Power Meter" // for combined power
        capability "Energy Meter" // for combined energy
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"

        command "reset"

        fingerprint mfr: "027A", prod: "A000", model: "A004", deviceJoinName: "Zooz Switch" //Zooz ZEN Power Strip
    }
    
    preferences {
        input name: "text", type: "text", title: "Text", description: "Enter Text", required: true
        // TODO configuration parameters
    }
}

def installed() {
    log.debug "Installed ${device.displayName}"
    // Health check every 62 minutes.
    sendEvent(name: "checkInterval", value: 62 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
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
	// When we receive a zwave response, treat that as online
    // FIXME: we shouldn't need to do this. Because this is a composite device, events aren't being generated for the strip, but for each child. Once we start creating events for the main composite device, this will not be necessary.
    sendEvent(name: "DeviceWatch-DeviceStatus", value: "online", data: [deviceScheme: "MIXED"])

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
    log.debug "parsed '${description}' to ${result.inspect()}"
    result
}

// cmd.endPoints includes the USB ports but we don't want to expose them as child devices since they cannot be controlled so hardcode to just include the outlets
def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelEndPointReport cmd, ep = null) {
    log.info "Multichannel endpoint report $cmd" + (ep ? " from endpoint $ep" : "")
    // MultiChannelEndPointReport(dynamic: false, endPoints: 7, identical: true, res11: false, res00: 0)
    if (!childDevices) {
        addChildSwitches(1..5)
        addChildUsbPorts(6..7)
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
        encap(zwave.configurationV1.configurationSet(parameterNumber: 1, size: 4, scaledConfigurationValue: 1)),   // after power recovery, 1 = all ports ON
        encap(zwave.configurationV1.configurationSet(parameterNumber: 2, size: 4, scaledConfigurationValue: 5)),   // makes device report every 5W change
        encap(zwave.configurationV1.configurationSet(parameterNumber: 3, size: 4, scaledConfigurationValue: 600)), // enabling power Wattage reports every 10 minutes
        encap(zwave.configurationV1.configurationSet(parameterNumber: 4, size: 4, scaledConfigurationValue: 600)), // enabling kWh energy reports every 10 minutes
        encap(zwave.configurationV1.configurationSet(parameterNumber: 35, size: 4, scaledConfigurationValue: 0)),  // Amps report frequency; 0=disabled
        encap(zwave.configurationV1.configurationSet(parameterNumber: 36, size: 4, scaledConfigurationValue: 0))   // Voltage report frequency; 0=disabled
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
    log.debug "Multichannel command ${cmd}" + (ep ? " from endpoint $ep" : "")
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
    def child = findDeviceByEndpoint(endpoint)
    child?.sendEvent(name: "switch", value: value, descriptionText: "Switch ${endpoint} is ${value}")
}

def findDeviceByEndpoint(endpoint) {
    return childDevices.find { it.deviceNetworkId.endsWith(":$endpoint") }
}

def zwaveEvent(physicalgraph.zwave.commands.meterv3.MeterReport cmd, ep = null) {
    log.debug "Meter ${cmd}" + (ep ? " from endpoint $ep" : "")
    def result = null
    if (ep) {
        def child = findDeviceByEndpoint(ep)
        result = child?.sendEvent(createMeterEventMap(cmd))
    } else {
        log.debug "Wattage change has been detected. Refreshing each endpoint"
        // return createEvent([isStateChange: false, descriptionText: "Wattage change has been detected. Refreshing each endpoint"])
    }
    
    // Sum up all the child attributes here.
    // TODO: wait until we've collected all endpoints first; otherwise this will send 5x events as each plug is received.
    if (cmd.scale == 0) {
        def energySum = childDevices?.collect { it.currentValue("energy") ?: 0 }?.sum()
        sendEvent(name: "energy", value: energySum, unit: "kWh")
        log.debug "Aggregate energy from child devices: $energySum kWh."
    } else if (cmd.scale == 2) {
        def powerSum = childDevices?.collect { it.currentValue("power") ?: 0 }?.sum()
        sendEvent(name: "power", value: powerSum, unit: "W")
        log.debug "Aggregate power from child devices: $powerSum W."
    }
    
    return result
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
	// TODO: no need for whole-strip on?
    // onOffCmd(0xFF)
}

def off() {
	// TODO: no need for whold-strip off?
    // onOffCmd(0x00)
}

// The Health Check capability uses the ???checkInterval??? attribute to determine the maximum number of seconds the device can go without generating new events.
// If the device hasn???t created any events within that amount of time, SmartThings executes the ???ping()??? command.
// If ping() does not generate any events, SmartThings marks the device as offline.
def ping() {
	log.info("ping(): refreshing status")
    refresh()
}

def childOnOff(deviceNetworkId, value) {
    def switchId = getEndpoint(deviceNetworkId)
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
    def endpoints = [1, 2, 3, 4, 5]
    sendHubCommand refresh(endpoints, includeMeterGet)
}

def childRefresh(deviceNetworkId, includeMeterGet = true) {
    def endpoint = getEndpoint(deviceNetworkId)
    if (endpoint != null) {
        sendHubCommand refresh([endpoint], includeMeterGet)
    }
}

def refresh(endpoints = [1, 2, 3, 4, 5], includeMeterGet = true) {
	log.info("refresh: $endpoints, $includeMeterGet")
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
    def switchId = getEndpoint(deviceNetworkId)
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

def getEndpoint(deviceNetworkId) {
    def split = deviceNetworkId?.split(":")
    if ((split.length > 2)) {
        return split[2] as Integer
    } else {
        return null
    }
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
    for (def endpoint : numberOfSwitches) {
        try {
            String childDni = "${device.deviceNetworkId}:plug:$endpoint"
            def componentLabel = device.displayName + " ${endpoint}"
            def childDthName = "Child Metering Switch"
            log.debug("JHH adding $childDni as plug-$endpoint")
            addChildDevice("chapterdream03931", childDthName, childDni, device.getHub().getId(), [
                completedSetup: true,
                label         : componentLabel,
                isComponent   : true,
                componentName : "plug-$endpoint",
                componentLabel: "Plug $endpoint",
            ])
        } catch (Exception e) {
            log.debug "Exception: ${e}"
        }
    }
}

private addChildUsbPorts(IntRange portRange) {
    log.debug "${device.displayName} - Executing addChildUsbPorts()"
    for (def endpoint : portRange) {
        try {
            String childDni = "${device.deviceNetworkId}:usb:$endpoint"
            def componentLabel = device.displayName + " USB ${endpoint}"
            def childDthName = "Child USB Port"
            log.debug("JHH adding $childDni as usb-$endpoint")
            // Reuse the usb port capability for now.
            addChildDevice("krlaframboise", childDthName, childDni, device.getHub().getId(), [
                completedSetup: true,
                label         : componentLabel,
                isComponent   : true,
                componentName : "usb-$endpoint",
                componentLabel: "USB ${endpoint - 1 + portRange.from}",
            ])
        } catch (Exception e) {
            log.debug "Exception: ${e}"
        }
    }
}
