/*
 * Copyright 2016 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.tuning.discovery.discoverers;

import opendct.capture.CaptureDevice;
import opendct.capture.CaptureDeviceIgnoredException;
import opendct.config.Config;
import opendct.config.OSVersion;
import opendct.config.options.BooleanDeviceOption;
import opendct.config.options.DeviceOption;
import opendct.config.options.DeviceOptionException;
import opendct.config.options.IntegerDeviceOption;
import opendct.tuning.discovery.*;
import opendct.tuning.hdhomerun.*;
import opendct.tuning.upnp.UpnpDiscoveredDeviceParent;
import opendct.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class HDHomeRunDiscoverer implements DeviceDiscoverer {
    private static final Logger logger = LogManager.getLogger(HDHomeRunDiscoverer.class);

    // Static information about this discovery method.
    private static String name = "HDHomeRun";
    private static String description = "Discovers capture devices available via HDHomeRun native protocol.";
    private static OSVersion[] supportedOS = new OSVersion[] {
            OSVersion.WINDOWS,
            OSVersion.LINUX
    };

    // Global UPnP device settings.
    private final static ConcurrentHashMap<String, DeviceOption> deviceOptions;
    private static IntegerDeviceOption retunePolling;
    private static BooleanDeviceOption autoMapReference;
    private static BooleanDeviceOption autoMapTuning;
    private static BooleanDeviceOption hdhrLock;
    private static IntegerDeviceOption controlRetryCount;
    private static IntegerDeviceOption broadcastInterval;

    // Detection configuration and state
    private static boolean enabled;
    private static String errorMessage;
    private DeviceLoader deviceLoader;

    private final HDHomeRunDiscovery discovery = new HDHomeRunDiscovery(HDHomeRunDiscovery.getBroadcast());

    private final ReentrantReadWriteLock discoveredDevicesLock = new ReentrantReadWriteLock();
    private final HashMap<Integer, HDHomeRunDiscoveredDevice> discoveredDevices = new HashMap<>();
    private final HashMap<Integer, HDHomeRunDiscoveredDeviceParent> discoveredParents = new HashMap<>();
    private final ConcurrentHashMap<Integer, HDHomeRunDevice> hdHomeRunDevices = new ConcurrentHashMap<>();

    static {
        enabled = Config.getBoolean("hdhr.discoverer_enabled", true);
        errorMessage = null;
        deviceOptions = new ConcurrentHashMap<>();

        try {
            retunePolling = new IntegerDeviceOption(
                    Config.getInteger("hdhr.retune_poll_s", 1),
                    false,
                    "Re-tune Polling Seconds",
                    "hdhr.retune_poll_s",
                    "This is the frequency in seconds to poll the producer to check if it" +
                            " is stalled.",
                    0,
                    Integer.MAX_VALUE
            );

            autoMapReference = new BooleanDeviceOption(
                    Config.getBoolean("hdhr.qam.automap_reference_lookup", true),
                    false,
                    "ClearQAM Auto-Map by Reference",
                    "hdhr.qam.automap_reference_lookup",
                    "This enables ClearQAM devices to look up their channels based on the" +
                            " frequencies and programs available on a capture device with" +
                            " a CableCARD installed. This works well if you have an" +
                            " InfiniTV device with a CableCARD installed available."
            );

            autoMapTuning = new BooleanDeviceOption(
                    Config.getBoolean("hdhr.qam.automap_tuning_lookup", false),
                    false,
                    "ClearQAM Auto-Map by Tuning",
                    "hdhr.qam.automap_reference_lookup",
                    "This enables ClearQAM devices to look up their channels by tuning" +
                            " into the channel on a capture device with a CableCARD" +
                            " installed and then getting the current frequency and" +
                            " program being used. This may be your only option if you are" +
                            " using only HDHomeRun Prime capture devices. The program" +
                            " always tries to get a channel by reference before resorting" +
                            " to this lookup method. It will also retain the results of" +
                            " the lookup so this doesn't need to happen the next time. If" +
                            " all tuners with a CableCARD installed are currently in use," +
                            " this method cannot be used and will fail to tune the channel."
            );

            hdhrLock = new BooleanDeviceOption(
                    Config.getBoolean("hdhr.locking", true),
                    false,
                    "HDHomeRun Locking",
                    "hdhr.locking",
                    "This enables when using HDHomeRun Native Tuning, the program to put" +
                            " the tuner in a locked state when it is in use. This should" +
                            " generally not be disabled. The affected capture devices need" +
                            " to be re-loaded for this setting to take effect."
            );

            controlRetryCount = new IntegerDeviceOption(
                    Config.getInteger("hdhr.retry_count", 2),
                    false,
                    "Communication Retry Count",
                    "hdhr.retry_count",
                    "This is the number of times the program will attempt to communicate with the" +
                            " HDHomeRun device before returning a IO error.",
                    0,
                    Integer.MAX_VALUE
            );

            broadcastInterval = new IntegerDeviceOption(
                    Config.getInteger("hdhr.broadcast_s", 58),
                    false,
                    "Discovery Broadcast Interval",
                    "hdhr.broadcast_ms",
                    "This is the interval in seconds that the program will send out a" +
                            " broadcast to locate HDHomeRun devices on the network. A value of 0" +
                            " will turn off discovery after the first broadcast.",
                    0,
                    Integer.MAX_VALUE
            );

            Config.mapDeviceOptions(
                    deviceOptions,
                    retunePolling,
                    autoMapReference,
                    autoMapTuning,
                    hdhrLock,
                    controlRetryCount
            );
        } catch (DeviceOptionException e) {
            logger.error("Unable to configure device options for HDHomeRunDiscoverer => ", e);
        }
    }

    @Override
    public String getName() {
        return HDHomeRunDiscoverer.name;
    }

    @Override
    public String getDescription() {
        return HDHomeRunDiscoverer.description;
    }

    @Override
    public boolean isEnabled() {
        return HDHomeRunDiscoverer.enabled;
    }

    @Override
    public synchronized void setEnabled(boolean enabled) {
        HDHomeRunDiscoverer.enabled = enabled;
        Config.setBoolean("hdhr.discoverer_enabled", enabled);
    }

    @Override
    public OSVersion[] getSupportedOS() {
        return HDHomeRunDiscoverer.supportedOS;
    }

    @Override
    public synchronized void startDetection(DeviceLoader deviceLoader) throws DiscoveryException {
        if (deviceLoader == null || !HDHomeRunDiscoverer.enabled || discovery.isRunning()) {
            return;
        }

        this.deviceLoader = deviceLoader;

        try {
            discovery.start(this);
        } catch (IOException e) {
            throw new DiscoveryException(e);
        }
    }

    @Override
    public synchronized void stopDetection() throws DiscoveryException {
        if (!discovery.isRunning()) {
            return;
        }

        discovery.stop();
    }

    @Override
    public synchronized void waitForStopDetection() throws InterruptedException {
        if (!discovery.isRunning()) {
            return;
        }

        discovery.waitForStop();

        discoveredDevicesLock.writeLock().lock();

        try {
            hdHomeRunDevices.clear();
            discoveredDevices.clear();
            discoveredParents.clear();
        } catch (Exception e) {
            logger.error("waitForStopDetection created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.writeLock().unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return discovery.isRunning();
    }

    public boolean isWaitingForDevices() {
        return deviceLoader == null || deviceLoader.isWaitingForDevices();
    }

    public void addDevices(HDHomeRunDevice discoveredDevice) {

        discoveredDevicesLock.writeLock().lock();

        try {
            // This prevents accidentally re-adding a device after detection has stopped.
            if (!discovery.isRunning()) {
                return;
            }

            HDHomeRunDevice updateDevice = hdHomeRunDevices.get(discoveredDevice.getDeviceId());

            if (updateDevice != null) {
                // This device has been detected before. We will only update the IP address.

                if (!(discoveredDevice.getIpAddress() == discoveredDevice.getIpAddress())) {
                    logger.info("HDHomeRun device '{}' changed its IP address from {} to {}.",
                            updateDevice.getUniqueDeviceName(),
                            updateDevice.getIpAddress().getHostAddress(),
                            discoveredDevice.getIpAddress().getHostAddress()
                    );

                    updateDevice.update(discoveredDevice);
                }

                return;
            }

            String uniqueParentName = discoveredDevice.getUniqueDeviceName();

            logger.info("Discovered a new HDHomeRun device '{}'.", uniqueParentName);

            HDHomeRunDiscoveredDeviceParent parentDevice = new HDHomeRunDiscoveredDeviceParent(
                    uniqueParentName,
                    uniqueParentName.hashCode(),
                    Util.getLocalIPForRemoteIP(discoveredDevice.getIpAddress()),
                    discoveredDevice
            );

            discoveredParents.put(parentDevice.getParentId(), parentDevice);

            for (int i = 0; i < discoveredDevice.getTunerCount(); i++) {
                String tunerName = discoveredDevice.getUniqueTunerName(i);

                HDHomeRunDiscoveredDevice newDevice = new HDHomeRunDiscoveredDevice(
                        tunerName,
                        tunerName.hashCode(),
                        parentDevice.getParentId(),
                        "HDHomeRun " + discoveredDevice.getSysHwModel() + " capture device.",
                        i
                );

                this.discoveredDevices.put(newDevice.getId(), newDevice);

                parentDevice.addChild(newDevice.getId());
            }

            hdHomeRunDevices.put(discoveredDevice.getDeviceId(), discoveredDevice);
        } catch (IOException e) {
            logger.error("Unable to communicate with HDHomeRun device '{}' => ",
                    discoveredDevice, e);

        } catch (GetSetException e) {
            logger.error("HDHomeRun device '{}' returned an error instead of a value => ",
                    discoveredDevice, e);

        } catch (Exception e) {
            logger.error("addDevices created an unexpected exception => ", e);
        } finally {
            discoveredDevicesLock.writeLock().unlock();
        }

    }

    @Override
    public String getErrorMessage() {
        return HDHomeRunDiscoverer.errorMessage;
    }

    @Override
    public int discoveredDevices() {
        int returnValue = 0;

        discoveredDevicesLock.readLock().lock();

        try {
            returnValue = discoveredDevices.size();
        } catch (Exception e) {
            logger.error("discoveredDevices created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        return returnValue;
    }

    @Override
    public DiscoveredDevice[] getAllDeviceDetails() {
        DiscoveredDevice[] returnValues = null;

        discoveredDevicesLock.readLock().lock();

        try {
            returnValues = new DiscoveredDevice[discoveredDevices.size()];

            int i = 0;
            for (Map.Entry<Integer, HDHomeRunDiscoveredDevice> discoveredDevice : discoveredDevices.entrySet()) {
                returnValues[i++] = discoveredDevice.getValue();
            }

        } catch (Exception e) {
            logger.error("getAllDeviceDetails created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        if (returnValues == null) {
            returnValues = new DiscoveredDevice[0];
        }

        return returnValues;
    }

    @Override
    public DiscoveredDevice getDeviceDetails(int deviceId) {
        DiscoveredDevice returnValue = null;

        discoveredDevicesLock.readLock().lock();

        try {
            returnValue = discoveredDevices.get(deviceId);
        } catch (Exception e) {
            logger.error("getDeviceDetails created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        return returnValue;
    }

    @Override
    public DiscoveredDeviceParent[] getAllDeviceParentDetails() {
        DiscoveredDeviceParent[] returnValues = null;

        discoveredDevicesLock.readLock().lock();

        try {
            returnValues = new UpnpDiscoveredDeviceParent[discoveredParents.size()];

            int i = 0;
            for (Map.Entry<Integer, HDHomeRunDiscoveredDeviceParent> discoveredParent : discoveredParents.entrySet()) {
                returnValues[i++] = discoveredParent.getValue();
            }

        } catch (Exception e) {
            logger.error("getAllDeviceParentDetails created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        if (returnValues == null) {
            returnValues = new UpnpDiscoveredDeviceParent[0];
        }

        return returnValues;
    }

    @Override
    public DiscoveredDeviceParent getDeviceParentDetails(int parentId) {
        DiscoveredDeviceParent deviceParent = null;

        discoveredDevicesLock.readLock().lock();

        try {
            deviceParent = discoveredParents.get(parentId);
        } catch (Exception e) {
            logger.error("getDeviceParentDetails created an unexpected exception while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        return deviceParent;
    }

    @Override
    public CaptureDevice loadCaptureDevice(int deviceId)
            throws CaptureDeviceIgnoredException, CaptureDeviceLoadException {

        CaptureDevice returnValue = null;
        HDHomeRunDiscoveredDevice discoveredDevice;
        CaptureDeviceLoadException loadException = null;

        discoveredDevicesLock.readLock().lock();

        try {
            discoveredDevice = discoveredDevices.get(deviceId);

            if (discoveredDevice != null) {
                returnValue = discoveredDevice.loadCaptureDevice();
            } else {
                loadException = new CaptureDeviceLoadException("Unable to create capture device" +
                        " because it was never detected.");
            }

        } catch (CaptureDeviceLoadException e) {
            loadException = e;
        } catch (Exception e) {
            logger.error("An unhandled exception happened in loadCaptureDevice while using" +
                    " discoveredDevicesLock => ", e);
        } finally {
            discoveredDevicesLock.readLock().unlock();
        }

        if (loadException != null) {
            throw loadException;
        }

        return returnValue;
    }

    @Override
    public DeviceOption[] getOptions() {
        return new DeviceOption[] {
                retunePolling,
                autoMapReference,
                autoMapTuning,
                hdhrLock,
                controlRetryCount
        };
    }

    @Override
    public void setOptions(DeviceOption... deviceOptions) throws DeviceOptionException {
        for (DeviceOption option : deviceOptions) {
            DeviceOption optionReference = HDHomeRunDiscoverer.deviceOptions.get(option.getProperty());

            if (optionReference == null) {
                continue;
            }

            if (optionReference.isArray()) {
                optionReference.setValue(option.getArrayValue());
            } else {
                optionReference.setValue(option.getValue());
            }

            Config.setDeviceOption(optionReference);
        }

        Config.saveConfig();
    }

    public static int getRetunePolling() {
        return retunePolling.getInteger();
    }

    public static boolean getAutoMapReference() {
        return autoMapReference.getBoolean();
    }

    public static boolean getAutoMapTuning() {
        return autoMapTuning.getBoolean();
    }

    public static boolean getHdhrLock() {
        return hdhrLock.getBoolean();
    }

    public static int getControlRetryCount() {
        return controlRetryCount.getInteger();
    }

    public static int getBroadcastInterval() {
        return broadcastInterval.getInteger();
    }
}
