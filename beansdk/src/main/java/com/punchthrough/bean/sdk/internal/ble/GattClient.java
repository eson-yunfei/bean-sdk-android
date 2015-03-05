package com.punchthrough.bean.sdk.internal.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import com.punchthrough.bean.sdk.internal.battery.BatteryProfile;
import com.punchthrough.bean.sdk.internal.device.DeviceProfile;
import com.punchthrough.bean.sdk.internal.exception.MetadataParsingException;
import com.punchthrough.bean.sdk.internal.serial.GattSerialTransportProfile;
import com.punchthrough.bean.sdk.internal.upload.firmware.FirmwareImageType;
import com.punchthrough.bean.sdk.internal.upload.firmware.FirmwareMetadata;
import com.punchthrough.bean.sdk.internal.upload.firmware.FirmwareUploadState;
import com.punchthrough.bean.sdk.internal.utility.Constants;
import com.punchthrough.bean.sdk.internal.utility.Misc;
import com.punchthrough.bean.sdk.message.BeanError;
import com.punchthrough.bean.sdk.message.Callback;
import com.punchthrough.bean.sdk.message.UploadProgress;
import com.punchthrough.bean.sdk.upload.FirmwareBundle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class GattClient {
    private static final String TAG = "GattClient";
    private final GattSerialTransportProfile mSerialProfile;
    private final DeviceProfile mDeviceProfile;
    private final BatteryProfile mBatteryProfile;
    private BluetoothGatt mGatt;
    private List<BaseProfile> mProfiles = new ArrayList<>(10);
    private Queue<Runnable> mOperationsQueue = new ArrayDeque<>(32);
    private boolean mOperationInProgress = false;
    private boolean mConnected = false;
    private boolean mDiscoveringServices = false;


    // These class variables are used for firmware uploads.
    /**
     * The maximum time, in ms, the client will wait for an update from the Bean before aborting the
     * firmware upload process and throwing an error
     */
    private static final int FIRMWARE_UPLOAD_TIMEOUT = 3000;
    /**
     * Once the last chunk is requested, wait this many ms for retransmission requests before we
     * assume the firmware upload is complete
     */
    private static final int FIRMWARE_COMPLETION_TIMEOUT = 500;
    /**
     * Max number of firmware blocks in flight at any given time
     */
    private static final int BLOCKS_IN_FLIGHT = 18;
    /**
     * When number of blocks in flight gets this low, send more blocks. We wait for the number to
     * get low so we can send a bunch of blocks at once.
     */
    private static final int SEND_BLOCKS_LOWER_LIMIT = 3;
    /**
     * The OAD Service contains the OAD Identify and Block characteristics
     */
    private static final UUID SERVICE_OAD = UUID.fromString("F000FFC0-0451-4000-B000-000000000000");
    /**
     * The OAD Identify characteristic is used to negotiate the start of a firmware transfer
     */
    private static final UUID CHAR_OAD_IDENTIFY = UUID.fromString("F000FFC1-0451-4000-B000-000000000000");
    /**
     * The OAD Block characteristic is used to send firmware chunks and confirm transfer completion
     */
    private static final UUID CHAR_OAD_BLOCK = UUID.fromString("F000FFC2-0451-4000-B000-000000000000");
    /**
     * The OAD Identify characteristic for this device. Assigned when firmware upload is started.
     */
    private BluetoothGattCharacteristic oadIdentify;
    /**
     * The OAD Block characteristic for this device. Assigned when firmware upload is started.
     */
    private BluetoothGattCharacteristic oadBlock;
    /**
     * True if the OAD Identify characteristic is notifying, false otherwise
     */
    private boolean oadIdentifyNotifying = false;
    /**
     * True if the OAD Block characteristic is notifying, false otherwise
     */
    private boolean oadBlockNotifying = false;
    /**
     * State of the current firmware upload process.
     */
    private FirmwareUploadState firmwareUploadState = FirmwareUploadState.INACTIVE;
    /**
     * Aborts firmware upload and throws an error if we go too long without a response from the CC.
     */
    private Timer firmwareStateTimeout;
    /**
     * Started when the last chunk is requested. The Bean indicates firmware update is complete by
     * requesting the last chunk then doing nothing.
     */
    private Timer firmwareCompletionTimeout;
    /**
     * Firmware bundle with A and B images to send
     */
    FirmwareBundle firmwareBundle;
    /**
     * Chunks of firmware to be sent in order
     */
    private List<byte[]> fwChunksToSend;
    /**
     * Storage for chunks for firmware image A. This takes a while, so we do it ahead of time.
     */
    private List<byte[]> fwChunksToSendA;
    /**
     * Storage for chunks for firmware image B. This takes a while, so we do it ahead of time.
     */
    private List<byte[]> fwChunksToSendB;
    /**
     * Used to keep track of firmware upload state.
     */
    private int nextChunk = 0;
    /**
     * used to keep track of firmware upload state.
     */
    private int nextChunkRequest = 0;
    /**
     * Called to inform the Bean class when firmware upload progress is made.
     */
    private Callback<UploadProgress> onProgress;
    /**
     * Called to inform the Bean class when firmware upload is complete.
     */
    private Runnable onComplete;
    /**
     * Called when an error causes the firmware upload to fail.
     */
    private Callback<BeanError> onError;


    private final BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fireConnectionStateChange(BluetoothGatt.STATE_DISCONNECTED);
                disconnect();
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mConnected = true;
            }
            fireConnectionStateChange(newState);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mDiscoveringServices = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireServicesDiscovered();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireCharacteristicsRead(characteristic);
            executeNextOperation();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireCharacteristicWrite(characteristic);
            executeNextOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

            if (uploadInProgress()) {

                if (isOADIdentifyCharacteristic(characteristic)) {
                    Log.d(TAG, "OAD Identify characteristic notified");

                    resetFirmwareStateTimeout();

                    if (firmwareUploadState == FirmwareUploadState.AWAIT_CURRENT_HEADER) {
                        prepareResponseHeader(characteristic.getValue());

                    } else if (firmwareUploadState == FirmwareUploadState.AWAIT_XFER_ACCEPT) {
                        // Existing header read, new header sent, Identify pinged ->
                        // Bean rejected firmware version
                        throwBeanError(BeanError.BEAN_REJECTED_FW);

                    }

                } else if (isOADBlockCharacteristic(characteristic)) {
                    Log.d(TAG, "OAD Block characteristic notified");

                    if (firmwareUploadState == FirmwareUploadState.AWAIT_XFER_ACCEPT) {
                        // Existing header read, new header sent, Block pinged ->
                        // Bean accepted firmware version, begin transfer
                        beginFirmwareTransfer();

                    } else if (firmwareUploadState == FirmwareUploadState.SEND_FW_CHUNKS) {
                        // We've already started sending blocks, and the Bean has responded with the
                        // block number it requests
                        int blockRequested = Misc.twoBytesToInt(
                                characteristic.getValue(), Constants.CC2540_BYTE_ORDER);
                        sendNextFwChunks(blockRequested);

                    }

                }
            }

            fireCharacteristicChanged(characteristic);
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireDescriptorRead(descriptor);
            executeNextOperation();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireDescriptorWrite(descriptor);
            executeNextOperation();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
            }
        }
    };

    public GattClient() {
        mSerialProfile = new GattSerialTransportProfile(this);
        mDeviceProfile = new DeviceProfile(this);
        mBatteryProfile = new BatteryProfile(this);
        mProfiles.add(mSerialProfile);
        mProfiles.add(mDeviceProfile);
        mProfiles.add(mBatteryProfile);
    }

    private void fireDescriptorRead(BluetoothGattDescriptor descriptor) {
        for (BaseProfile profile : mProfiles) {
            profile.onDescriptorRead(this, descriptor);
        }
    }

    private synchronized void queueOperation(Runnable operation) {
        mOperationsQueue.offer(operation);
        if (!mOperationInProgress) {
            executeNextOperation();
        }
    }

    private synchronized void executeNextOperation() {
        Runnable operation = mOperationsQueue.poll();
        if (operation != null) {
            mOperationInProgress = true;
            operation.run();
        } else {
            mOperationInProgress = false;
        }
    }

    public void connect(Context context, BluetoothDevice device) {
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
        mConnected = false;
        mGatt = device.connectGatt(context, false, mBluetoothGattCallback);
    }

    private void fireDescriptorWrite(BluetoothGattDescriptor descriptor) {
        for (BaseProfile profile : mProfiles) {
            profile.onDescriptorWrite(this, descriptor);
        }
    }

    private void fireCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicChanged(this, characteristic);
        }
    }

    private void fireCharacteristicWrite(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicWrite(this, characteristic);
        }
    }

    private void fireCharacteristicsRead(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicRead(this, characteristic);
        }
    }

    private void fireServicesDiscovered() {
        for (BaseProfile profile : mProfiles) {
            profile.onServicesDiscovered(this);
        }
    }

    private synchronized void fireConnectionStateChange(int newState) {
        if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            mOperationsQueue.clear();
            mOperationInProgress = false;
            mConnected = false;
        } else if (newState == BluetoothGatt.STATE_CONNECTED) {
            mConnected = true;
        }
        for (BaseProfile profile : mProfiles) {
            profile.onConnectionStateChange(newState);
        }
    }

    public List<BluetoothGattService> getServices() {
        return mGatt.getServices();
    }

    public BluetoothGattService getService(UUID uuid) {
        return mGatt.getService(uuid);
    }

    public boolean discoverServices() {
        if (mDiscoveringServices) {
            return true;
        }
        mDiscoveringServices = true;
        return mGatt.discoverServices();
    }

    public synchronized boolean readCharacteristic(final BluetoothGattCharacteristic characteristic) {
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    mGatt.readCharacteristic(characteristic);
                }
            }
        });
        return true;
    }

    public synchronized boolean writeCharacteristic(final BluetoothGattCharacteristic characteristic) {
        final byte[] value = characteristic.getValue();
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    characteristic.setValue(value);
                    mGatt.writeCharacteristic(characteristic);
                }
            }
        });
        return true;
    }

    public boolean readDescriptor(final BluetoothGattDescriptor descriptor) {
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    mGatt.readDescriptor(descriptor);
                }
            }
        });
        return true;
    }

    public boolean writeDescriptor(final BluetoothGattDescriptor descriptor) {
        final byte[] value = descriptor.getValue();
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    descriptor.setValue(value);
                    mGatt.writeDescriptor(descriptor);
                }
            }
        });
        return true;
    }

    public boolean readRemoteRssi() {
        return mGatt.readRemoteRssi();
    }

    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enable) {
        return mGatt.setCharacteristicNotification(characteristic, enable);
    }

    private boolean connect() {
        return mGatt != null && mGatt.connect();
    }

    public void disconnect() {
        close();
        throwBeanError(BeanError.UNKNOWN);
    }

    private synchronized void close() {
        if (mGatt != null) {
            mGatt.close();
        }
        mGatt = null;
    }

    public GattSerialTransportProfile getSerialProfile() {
        return mSerialProfile;
    }

    public DeviceProfile getDeviceProfile() {
        return mDeviceProfile;
    }

    public BatteryProfile getBatteryProfile() {
        return mBatteryProfile;
    }

    public void programWithFirmware(FirmwareBundle bundle, Callback<UploadProgress> onProgress,
                                    Runnable onComplete, Callback<BeanError> onError) {

        Log.d(TAG, "Programming Bean with firmware");

        // Ensure Bean is connected and services have been discovered
        if (!mConnected) {
            onError.onResult(BeanError.NOT_CONNECTED);
        }
        if (mGatt.getServices() == null) {
            onError.onResult(BeanError.SERVICES_NOT_DISCOVERED);
        }

        // Set event handlers
        this.onProgress = onProgress;
        this.onComplete = onComplete;
        this.onError = onError;

        // Save firmware bundle so we have both images when response header is received
        this.firmwareBundle = bundle;

        Log.d(TAG, "Preparing firmware chunks (give me a second)");

        // Prepare chunks: doing this during the FW upload process takes too long
        fwChunksToSendA = bundle.imageA().chunks();
        fwChunksToSendB = bundle.imageB().chunks();

        verifyNotifyEnabled();

    }

    private void resetFirmwareUploadState() {

        firmwareUploadState = FirmwareUploadState.INACTIVE;
        stopFirmwareStateTimeout();
        nextChunk = 0;
        nextChunkRequest = 0;

    }

    private void verifyNotifyEnabled() {
        Log.d(TAG, "Firmware chunks prepared, verifying OAD notifications are enabled");
        // Ensure all characteristics are discovered and notifying
        if (oadIdentify != null && oadBlock != null &&
                oadIdentifyNotifying && oadBlockNotifying) {
            requestCurrentHeader();
        } else {
            enableOADNotifications();
        }
    }

    private void enableOADNotifications() {
        Log.d(TAG, "Enabling OAD notifications");
        firmwareUploadState = FirmwareUploadState.AWAIT_NOTIFY_ENABLED;

        BluetoothGattService oadService = mGatt.getService(SERVICE_OAD);
        if (oadService == null) {
            throwBeanError(BeanError.MISSING_OAD_SERVICE);
            return;
        }

        oadIdentify = oadService.getCharacteristic(CHAR_OAD_IDENTIFY);
        if (oadIdentify == null) {
            throwBeanError(BeanError.MISSING_OAD_IDENTIFY);
            return;
        }

        oadBlock = oadService.getCharacteristic(CHAR_OAD_BLOCK);
        if (oadBlock == null) {
            throwBeanError(BeanError.MISSING_OAD_BLOCK);
            return;
        }

        oadIdentifyNotifying = enableNotifyForChar(oadIdentify);
        oadBlockNotifying = enableNotifyForChar(oadBlock);
        if (oadIdentifyNotifying && oadBlockNotifying) {
            Log.d(TAG, "Enable notifications successful");
            requestCurrentHeader();
        } else {
            throwBeanError(BeanError.ENABLE_OAD_NOTIFY_FAILED);
        }
    }

    // https://developer.android.com/guide/topics/connectivity/bluetooth-le.html#notification
    private boolean enableNotifyForChar(BluetoothGattCharacteristic characteristic) {
        boolean result = mGatt.setCharacteristicNotification(characteristic, true);

        String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mGatt.writeDescriptor(descriptor);
        if (result) {
            Log.d(TAG, "Enabled notify for characteristic: " + characteristic.getUuid());
        } else {
            Log.e(TAG, "Enable notify failed for characteristic: " + characteristic.getUuid());
        }
        return result;
    }

    private void requestCurrentHeader() {

        Log.d(TAG, "Requesting current header");
        firmwareUploadState = FirmwareUploadState.AWAIT_CURRENT_HEADER;

        // To request the current header, write [0x00] to OAD Identify
        writeToCharacteristic(oadIdentify, new byte[]{0x00});

    }

    private void prepareResponseHeader(byte[] existingHeader) {

        FirmwareMetadata oldMeta;
        try {
            oldMeta = FirmwareMetadata.fromPayload(existingHeader);

        } catch (MetadataParsingException e) {
            throwBeanError(BeanError.UNPARSABLE_FW_VERSION);
            return;

        }

        FirmwareMetadata newMeta;

        // If the Bean has image A, send image B and vice versa.

        if (oldMeta.type() == FirmwareImageType.A) {
            newMeta = firmwareBundle.imageB().metadata();
            fwChunksToSend = fwChunksToSendB;

        } else if (oldMeta.type() == FirmwareImageType.B) {
            newMeta = firmwareBundle.imageA().metadata();
            fwChunksToSend = fwChunksToSendA;

        } else {
            throwBeanError(BeanError.UNPARSABLE_FW_VERSION);
            return;

        }

        Log.d(TAG, "Firmware to be replaced: " + oldMeta);
        Log.d(TAG, "Firmware to be sent: " + newMeta);

        firmwareUploadState = FirmwareUploadState.AWAIT_XFER_ACCEPT;

        // Write the new image metadata
        writeToCharacteristic(oadIdentify, newMeta.toPayload());

    }

    private void beginFirmwareTransfer() {

        Log.d(TAG, "Bean accepted new firmware. Beginning firmware transfer");

        firmwareUploadState = FirmwareUploadState.SEND_FW_CHUNKS;
        nextChunk = 0;
        nextChunkRequest = 0;

        sendNextFwChunks(0);

    }

    private void sendNextFwChunks(int requestedChunk) {

        Log.d(TAG, "FW block " + requestedChunk + " requested");

        if (requestedChunk < nextChunkRequest) {
            // Bean missed a block and requested a retransmit. Roll back nextChunkRequest because we
            // expect lots of retransmit requests to occur for the same block.
            Log.d(TAG, "FW block " + requestedChunk + " lost in transit; resending");
            nextChunkRequest -= nextChunk - requestedChunk - 1;
            nextChunk = requestedChunk;
        }
        nextChunkRequest++;

        if (nextChunk - requestedChunk < SEND_BLOCKS_LOWER_LIMIT) {

            // Lots of blocks have been sent - now we have several blocks to send at once
            while ( nextChunk - requestedChunk < BLOCKS_IN_FLIGHT &&
                    nextChunk < fwChunksToSend.size() ) {

                sendSingleChunk(nextChunk);
                nextChunk++;

            }
        }

        if (requestedChunk == fwChunksToSend.size() - 1) {
            // Bean requested last chunk. If we don't hear any retransmit requests within a timeout,
            // then we're done!
            Log.d(TAG, "Last chunk requested");
            stopFirmwareStateTimeout();
            resetFirmwareCompletionTimeout();

        }

    }

    private void sendSingleChunk(int chunkIndex) {
        resetFirmwareStateTimeout();
        byte[] chunkToSend = fwChunksToSend.get(chunkIndex);
        boolean result = writeToCharacteristic(oadBlock, chunkToSend);
        if (result) {
            Log.d(TAG, "FW block " + chunkIndex + " sent");
        } else {
            Log.e(TAG, "FW block " + chunkIndex + " failed to send");
        }
    }
    
    private void stopFirmwareStateTimeout() {
        if (firmwareStateTimeout != null) {
            firmwareStateTimeout.cancel();
            firmwareStateTimeout = null;
        }
    }

    private void stopFirmwareCompletionTimeout() {
        if (firmwareCompletionTimeout != null) {
            firmwareCompletionTimeout.cancel();
            firmwareCompletionTimeout = null;
        }
    }

    private void resetFirmwareStateTimeout() {
        TimerTask onTimeout = new TimerTask() {
            @Override
            public void run() {

                Log.e(TAG, "Firmware update state timed out: " + firmwareUploadState);

                if (firmwareUploadState == FirmwareUploadState.AWAIT_CURRENT_HEADER) {
                    throwBeanError(BeanError.FW_VER_REQ_TIMEOUT);

                } else if (firmwareUploadState == FirmwareUploadState.AWAIT_XFER_ACCEPT) {
                    throwBeanError(BeanError.FW_START_TIMEOUT);

                } else if (firmwareUploadState == FirmwareUploadState.SEND_FW_CHUNKS) {
                    throwBeanError(BeanError.FW_TRANSFER_TIMEOUT);

                }

            }
        };

        stopFirmwareStateTimeout();
        firmwareStateTimeout = new Timer();
        firmwareStateTimeout.schedule(onTimeout, FIRMWARE_UPLOAD_TIMEOUT);
    }

    private void resetFirmwareCompletionTimeout() {
        TimerTask onTimeout = new TimerTask() {
            @Override
            public void run() {
                onComplete.run();
            }
        };

        stopFirmwareCompletionTimeout();
        firmwareCompletionTimeout = new Timer();
        firmwareCompletionTimeout.schedule(onTimeout, FIRMWARE_COMPLETION_TIMEOUT);
    }

    private void throwBeanError(BeanError error) {
        resetFirmwareUploadState();
        if (onError != null) {
            onError.onResult(error);
        }
    }

    private boolean uploadInProgress() {
        return firmwareUploadState != FirmwareUploadState.INACTIVE;
    }

    private boolean isOADBlockCharacteristic(BluetoothGattCharacteristic charc) {
        UUID uuid = charc.getUuid();
        return uuid.equals(CHAR_OAD_BLOCK);
    }

    private boolean isOADIdentifyCharacteristic(BluetoothGattCharacteristic charc) {
        UUID uuid = charc.getUuid();
        return uuid.equals(CHAR_OAD_IDENTIFY);
    }

    private boolean writeToCharacteristic(BluetoothGattCharacteristic charc, byte[] data) {
        charc.setValue(data);
        boolean result = mGatt.writeCharacteristic(charc);
        if (result) {
            Log.d(TAG, "Wrote to characteristic: " + charc.getUuid() +
                    ", data: " + Arrays.toString(data));
        } else {
            Log.e(TAG, "Write failed to characteristic: " + charc.getUuid() +
                    ", data: " + Arrays.toString(data));
        }
        return result;
    }
}
