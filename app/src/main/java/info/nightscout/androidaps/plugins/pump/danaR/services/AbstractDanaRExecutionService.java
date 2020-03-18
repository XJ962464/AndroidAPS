package info.nightscout.androidaps.plugins.pump.danaR.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import dagger.android.DaggerService;
import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.events.EventPumpStatusChanged;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPump;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MessageBase;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgBolusStop;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryAlarm;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryBasalHour;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryBolus;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryCarbo;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryDailyInsulin;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryError;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryGlucose;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistoryRefill;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgHistorySuspend;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgPCCommStart;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgPCCommStop;
import info.nightscout.androidaps.plugins.pump.danaR.comm.RecordTypes;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.ToastUtils;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.sharedPreferences.SP;

/**
 * Created by mike on 28.01.2018.
 */

public abstract class AbstractDanaRExecutionService extends DaggerService {
    @Inject HasAndroidInjector injector;
    @Inject AAPSLogger aapsLogger;
    @Inject RxBusWrapper rxBus;
    @Inject SP sp;
    @Inject Context context;
    @Inject ResourceHelper resourceHelper;
    @Inject DanaRPump danaRPump;

    protected String mDevName;

    protected BluetoothSocket mRfcommSocket;
    protected BluetoothDevice mBTDevice;

    protected Treatment mBolusingTreatment = null;

    protected boolean mConnectionInProgress = false;
    protected boolean mHandshakeInProgress = false;

    protected AbstractSerialIOThread mSerialIOThread;

    protected IBinder mBinder;

    protected final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    protected long lastApproachingDailyLimit = 0;


    public abstract boolean updateBasalsInPump(final Profile profile);

    public abstract void connect();

    public abstract void getPumpStatus();

    public abstract PumpEnactResult loadEvents();

    public abstract boolean bolus(double amount, int carbs, long carbtime, final Treatment t);

    public abstract boolean highTempBasal(int percent, int durationInMinutes); // Rv2 only

    public abstract boolean tempBasalShortDuration(int percent, int durationInMinutes); // Rv2 only

    public abstract boolean tempBasal(int percent, int durationInHours);

    public abstract boolean tempBasalStop();

    public abstract boolean extendedBolus(double insulin, int durationInHalfHours);

    public abstract boolean extendedBolusStop();

    public abstract PumpEnactResult setUserOptions();

    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                aapsLogger.debug(LTag.PUMP, "Device was disconnected " + device.getName());//Device was disconnected
                if (mBTDevice != null && mBTDevice.getName() != null && mBTDevice.getName().equals(device.getName())) {
                    if (mSerialIOThread != null) {
                        mSerialIOThread.disconnect("BT disconnection broadcast");
                    }
                    rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED));
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public boolean isConnected() {
        return mRfcommSocket != null && mRfcommSocket.isConnected();
    }

    public boolean isConnecting() {
        return mConnectionInProgress;
    }

    public boolean isHandshakeInProgress() {
        return isConnected() && mHandshakeInProgress;
    }

    public void finishHandshaking() {
        mHandshakeInProgress = false;
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED, 0));
    }

    public void disconnect(String from) {
        if (mSerialIOThread != null)
            mSerialIOThread.disconnect(from);
    }

    public void stopConnecting() {
        if (mSerialIOThread != null)
            mSerialIOThread.disconnect("stopConnecting");
    }

    protected void getBTSocketForSelectedPump() {
        mDevName = sp.getString(resourceHelper.gs(R.string.key_danar_bt_name), "");
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter != null) {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();

            for (BluetoothDevice device : bondedDevices) {
                if (mDevName.equals(device.getName())) {
                    mBTDevice = device;
                    try {
                        mRfcommSocket = mBTDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                    } catch (IOException e) {
                        aapsLogger.error("Error creating socket: ", e);
                    }
                    break;
                }
            }
        } else {
            ToastUtils.showToastInUiThread(context.getApplicationContext(), resourceHelper.gs(R.string.nobtadapter));
        }
        if (mBTDevice == null) {
            ToastUtils.showToastInUiThread(context.getApplicationContext(), resourceHelper.gs(R.string.devicenotfound));
        }
    }

    public void bolusStop() {
        aapsLogger.debug(LTag.PUMP, "bolusStop >>>>> @ " + (mBolusingTreatment == null ? "" : mBolusingTreatment.insulin));
        MsgBolusStop stop = new MsgBolusStop(aapsLogger, rxBus, resourceHelper, danaRPump);
        danaRPump.setBolusStopForced(true);
        if (isConnected()) {
            mSerialIOThread.sendMessage(stop);
            while (!danaRPump.getBolusStopped()) {
                mSerialIOThread.sendMessage(stop);
                SystemClock.sleep(200);
            }
        } else {
            danaRPump.setBolusStopped(true);
        }
    }

    public PumpEnactResult loadHistory(byte type) {
        PumpEnactResult result = new PumpEnactResult(injector);
        if (!isConnected()) return result;
        MessageBase msg = null;
        switch (type) {
            case RecordTypes.RECORD_TYPE_ALARM:
                msg = new MsgHistoryAlarm(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_BASALHOUR:
                msg = new MsgHistoryBasalHour(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_BOLUS:
                msg = new MsgHistoryBolus(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_CARBO:
                msg = new MsgHistoryCarbo(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_DAILY:
                msg = new MsgHistoryDailyInsulin(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_ERROR:
                msg = new MsgHistoryError(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_GLUCOSE:
                msg = new MsgHistoryGlucose(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_REFILL:
                msg = new MsgHistoryRefill(aapsLogger, rxBus);
                break;
            case RecordTypes.RECORD_TYPE_SUSPEND:
                msg = new MsgHistorySuspend(aapsLogger, rxBus);
                break;
        }
        danaRPump.setHistoryDoneReceived(false);
        mSerialIOThread.sendMessage(new MsgPCCommStart(aapsLogger));
        SystemClock.sleep(400);
        mSerialIOThread.sendMessage(msg);
        while (!danaRPump.getHistoryDoneReceived() && mRfcommSocket.isConnected()) {
            SystemClock.sleep(100);
        }
        SystemClock.sleep(200);
        mSerialIOThread.sendMessage(new MsgPCCommStop(aapsLogger));
        result.success = true;
        result.comment = "OK";
        return result;
    }

    protected void waitForWholeMinute() {
        while (true) {
            long time = DateUtil.now();
            long timeToWholeMinute = (60000 - time % 60000);
            if (timeToWholeMinute > 59800 || timeToWholeMinute < 3000)
                break;
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.waitingfortimesynchronization, (int) (timeToWholeMinute / 1000))));
            SystemClock.sleep(Math.min(timeToWholeMinute, 100));
        }
    }
}
