/**
 *  Copyright 2018 SRPOL
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
    definition(
        name: "Child Metering Switch",
        namespace: "chapterdream03931",
        author: "SmartThings",
        mnmn: "SmartThingsCommunity",
        vid: "b5e9135d-dd6e-372e-8248-a8fafb92d645"
    ) {
        capability "Switch"
        capability "Power Meter"
        capability "Energy Meter"
        capability "Refresh"

        command "reset"
    }
}

def on() {
    parent.childOnOff(device.deviceNetworkId, 0xFF)
}

def off() {
    parent.childOnOff(device.deviceNetworkId, 0x00)
}

def refresh() {
    parent.childRefresh(device.deviceNetworkId)
}

def reset() {
    parent.childReset(device.deviceNetworkId)
}

def installed() {
	// anything?
}
