package com.greencis.wave_n_click;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity implements SensorEventListener {

    private enum CurrentLayout {STANDARD, MOUSE, MOUSE_WHEEL, SEND_TEXT, MAP_KEYS}
    private enum TouchMode {NONE, DOWN, MOVE}

    private ArrayAdapter<String> arrayAdapterNewDevices;
    private SensorManager sensorManager;
    private Sensor sensorAccelerometer;
    private Sensor sensorGravity;
    private Sensor sensorOrientation;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outStream;

    private final static int REQUEST_ENABLE_BT = 1;
    private final static String BT_DELIM_DATA = Character.toString((char) 0x02); // separates command data
    private final static String BT_DELIM_COMMAND = Character.toString((char) 0x03); // separates groups of sensors' data
    private final static String BT_DELIM_VALUE = Character.toString((char) 0x04); // separates sensor data in each group

    private final static UUID MY_UUID = UUID.fromString("94292871-3487-4027-a725-cdda07013a0f");
    private String address = ""; // MAC address of Bluetooth server device

    private CurrentLayout layoutSelected = CurrentLayout.STANDARD;
    private boolean bluetoothOn = false;
    private String buttonsPressed = "";
    private String mouseButtonsPressed = "";
    private String editText = "";
    private String[] commands;
    private int screenHeight;
    private int screenWidth;
    private String[][] sensorsData = new String[3][];

    private float touchFromX, touchFromY;
    private float touchDeltaX, touchDeltaY;
    private float touchOldDist, touchScale;
    private TouchMode touchMode = TouchMode.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Wave'n'Click - Select a device");
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorOrientation = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);

        EditText editTextSendText = (EditText) findViewById(R.id.editTextSendText);
        EditText editTextMapKeys = (EditText) findViewById(R.id.editTextMapKeys);
        Button buttonLMB = (Button) findViewById(R.id.buttonLMB);
        Button buttonRMB = (Button) findViewById(R.id.buttonRMB);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screenHeight = displayMetrics.heightPixels;
        screenWidth = displayMetrics.widthPixels;

        refreshButtons();

        editTextSendText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                    editText += v.getText().toString();
                    v.setText("");
                }
                return true;
            }
        });

        editTextMapKeys.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                try {
                    File file = new File(getExternalFilesDir(null), "options.txt");
                    FileWriter fw = new FileWriter(file);
                    fw.write(((EditText) findViewById(R.id.editTextMapKeys)).getText().toString());
                    fw.close();
                } catch (Exception e) {
                    // Never mind, actually... But let's warn a user
                    Toast.makeText(getBaseContext(), "Warning: Failed to save options.txt", Toast.LENGTH_LONG).show();
                }
                refreshButtons();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        buttonLMB.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mouseButtonsPressed += "Ll"; // mouse button hold, then released
            }
        });

        buttonRMB.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mouseButtonsPressed += "Rr"; // mouse button hold, then released
            }
        });

        buttonLMB.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent motion) {
                switch (motion.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mouseButtonsPressed += "L";
                        break;
                    case MotionEvent.ACTION_UP:
                        mouseButtonsPressed += "l";
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        buttonRMB.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent motion) {
                switch (motion.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mouseButtonsPressed += "R";
                        break;
                    case MotionEvent.ACTION_UP:
                        mouseButtonsPressed += "r";
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            finish();
        }
        if (!btAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        ArrayAdapter<String> arrayAdapterPairedDevices = new ArrayAdapter<String>(this, R.layout.device_name);
        arrayAdapterNewDevices = new ArrayAdapter<String>(this, R.layout.device_name);

        ListView listViewPairedDevices = (ListView) findViewById(R.id.listViewPairedDevices);
        listViewPairedDevices.setAdapter(arrayAdapterPairedDevices);
        listViewPairedDevices.setOnItemClickListener(deviceClickListener);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(receiver, filter);

        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(receiver, filter);

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                arrayAdapterPairedDevices.add(device.getName() + "\n" + device.getAddress());
            }
        } else { // TODO: handle it in more proper way
            arrayAdapterPairedDevices.add("Sorry, no paired devices :(");
        }
    }

    private OnItemClickListener deviceClickListener = new OnItemClickListener() {
        public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
            // Don't need to discover devices, since we're about to connect
            btAdapter.cancelDiscovery();

            String info = ((TextView) v).getText().toString();
            address = info.substring(info.length() - 17); // MAC address is last 17 chars in the View

            findViewById(R.id.layout1).setVisibility(View.VISIBLE);
            findViewById(R.id.layout2).setVisibility(View.VISIBLE);
            findViewById(R.id.layout3).setVisibility(View.VISIBLE);
            findViewById(R.id.layout4).setVisibility(View.VISIBLE);
            findViewById(R.id.layout5).setVisibility(View.VISIBLE);
            findViewById(R.id.layout6).setVisibility(View.VISIBLE);

            findViewById(R.id.editTextSendText).setVisibility(View.GONE);
            findViewById(R.id.listViewPairedDevices).setVisibility(View.GONE);
            setTitle("Wave'n'Click - Standard mode");

            bluetoothOn = true;
            connectBT();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // If the device is already paired, skip it, because it's been listed already
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    arrayAdapterNewDevices.add(device.getName() + "\n" + device.getAddress());
                }
            } else if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                setProgressBarIndeterminateVisibility(false);
                if (arrayAdapterNewDevices.getCount() == 0) {
                    arrayAdapterNewDevices.add("Sorry, no devices found :(");
                }
            }
        }
    };

    // We need to keep this to allow MainActivity class to implement SensorEventListener
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuStandard:
                findViewById(R.id.layout1).setVisibility(View.VISIBLE);
                findViewById(R.id.layout2).setVisibility(View.VISIBLE);
                findViewById(R.id.layout3).setVisibility(View.VISIBLE);
                findViewById(R.id.layout4).setVisibility(View.VISIBLE);
                findViewById(R.id.layout5).setVisibility(View.VISIBLE);
                findViewById(R.id.layout6).setVisibility(View.VISIBLE);
                findViewById(R.id.editTextSendText).setVisibility(View.GONE);
                findViewById(R.id.editTextMapKeys).setVisibility(View.GONE);
                findViewById(R.id.layoutMouseButtons).setVisibility(View.GONE);
                setTitle("Wave'n'Click - Standard mode");
                layoutSelected = CurrentLayout.STANDARD;
                return true;
            case R.id.menuMouse:
                findViewById(R.id.layout1).setVisibility(View.GONE);
                findViewById(R.id.layout2).setVisibility(View.GONE);
                findViewById(R.id.layout3).setVisibility(View.GONE);
                findViewById(R.id.layout4).setVisibility(View.GONE);
                findViewById(R.id.layout5).setVisibility(View.GONE);
                findViewById(R.id.layout6).setVisibility(View.GONE);
                findViewById(R.id.editTextSendText).setVisibility(View.GONE);
                findViewById(R.id.editTextMapKeys).setVisibility(View.GONE);
                findViewById(R.id.layoutMouseButtons).setVisibility(View.VISIBLE);
                setTitle("Wave'n'Click - Mouse mode");
                layoutSelected = CurrentLayout.MOUSE;
                return true;
            case R.id.menuMouseWheel:
                findViewById(R.id.layout1).setVisibility(View.GONE);
                findViewById(R.id.layout2).setVisibility(View.GONE);
                findViewById(R.id.layout3).setVisibility(View.GONE);
                findViewById(R.id.layout4).setVisibility(View.GONE);
                findViewById(R.id.layout5).setVisibility(View.GONE);
                findViewById(R.id.layout6).setVisibility(View.GONE);
                findViewById(R.id.editTextSendText).setVisibility(View.GONE);
                findViewById(R.id.editTextMapKeys).setVisibility(View.GONE);
                findViewById(R.id.layoutMouseButtons).setVisibility(View.GONE);
                setTitle("Wave'n'Click - Mouse wheel mode");
                layoutSelected = CurrentLayout.MOUSE_WHEEL;
                return true;
            case R.id.menuSendText:
                findViewById(R.id.layout1).setVisibility(View.GONE);
                findViewById(R.id.layout2).setVisibility(View.GONE);
                findViewById(R.id.layout3).setVisibility(View.GONE);
                findViewById(R.id.layout4).setVisibility(View.GONE);
                findViewById(R.id.layout5).setVisibility(View.GONE);
                findViewById(R.id.layout6).setVisibility(View.GONE);
                findViewById(R.id.editTextSendText).setVisibility(View.VISIBLE);
                findViewById(R.id.editTextMapKeys).setVisibility(View.GONE);
                findViewById(R.id.layoutMouseButtons).setVisibility(View.GONE);
                setTitle("Wave'n'Click - Send text");
                layoutSelected = CurrentLayout.SEND_TEXT;
                return true;
            case R.id.menuMapKeys:
                findViewById(R.id.layout1).setVisibility(View.GONE);
                findViewById(R.id.layout2).setVisibility(View.GONE);
                findViewById(R.id.layout3).setVisibility(View.GONE);
                findViewById(R.id.layout4).setVisibility(View.GONE);
                findViewById(R.id.layout5).setVisibility(View.GONE);
                findViewById(R.id.layout6).setVisibility(View.GONE);
                findViewById(R.id.editTextSendText).setVisibility(View.GONE);
                findViewById(R.id.editTextMapKeys).setVisibility(View.VISIBLE);
                findViewById(R.id.layoutMouseButtons).setVisibility(View.GONE);
                setTitle("Wave'n'Click - Keys mapping");
                String et2_text = "";
                for (int i = 0; i < 11; ++i) {
                    et2_text += "\"" + commands[i * 2] + "\",\"" + commands[i * 2 + 1] + "\"\r\n";
                }
                ((EditText) findViewById(R.id.editTextMapKeys)).setText(et2_text);
                layoutSelected = CurrentLayout.MAP_KEYS;
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        super.onPause();
        if (bluetoothOn) {
            if (outStream != null) {
                try {
                    outStream.flush();
                } catch (IOException e) {
                    errorExit("Fatal Error", "Failed to flush output stream: " + e.getMessage());
                }
            }

            try {
                btSocket.close();
            } catch (IOException e2) {
                errorExit("Fatal Error", "Failed to close socket: " + e2.getMessage());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // TODO: add support for more sensors
        if (sensorAccelerometer != null) {
            sensorManager.registerListener(this, sensorAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorGravity != null) {
            sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_UI);
        }
        if (sensorOrientation != null) {
            sensorManager.registerListener(this, sensorOrientation, SensorManager.SENSOR_DELAY_UI);
        }

        connectBT();
    }

    private void connectBT() {
        if (!bluetoothOn) {
            return;
        }

        BluetoothDevice device = btAdapter.getRemoteDevice(address);
        try {
            btSocket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
        } catch (IOException e) {
            errorExit("Fatal Error", "Socket creation failed: " + e.getMessage());
        }

        btAdapter.cancelDiscovery();

        try {
            btSocket.connect();
        } catch (IOException e) {
            try {
                btSocket.close();
            } catch (IOException e2) {
                errorExit("Fatal Error", "Unable to close socket during connection failure: " + e2.getMessage());
            }
        }

        try {
            outStream = btSocket.getOutputStream();
        } catch (IOException e) {
            errorExit("Fatal Error", "btSocket output stream creation failed: " + e.getMessage());
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                // Data is sent each time the accelerometer hits this event...
                // TODO: make data sending independent from any sensors.
                String infoX = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_X]);
                String infoY = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Y]);
                String infoZ = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Z]);
                sensorsData[0] = new String[]{infoX, infoY, infoZ};
                String allSensors = "";
                for (String[] currentSensorData : sensorsData) {
                    if (currentSensorData != null) {
                        for (String currentFieldData : currentSensorData) {
                            allSensors += currentFieldData + BT_DELIM_VALUE;
                        }
                    }
                    allSensors += BT_DELIM_COMMAND;
                }

                float t_scale = 0, t_amountX = 0, t_amountY = 0;
                if (touchMode != TouchMode.MOVE && touchScale != 0) {
                    t_scale = touchScale;
                }
                if (touchMode == TouchMode.DOWN) {
                    t_amountX = touchDeltaX;
                    t_amountY = touchDeltaY;
                }
                String btCommand = ""; // message we are going to send
                btCommand += infoX + BT_DELIM_DATA; /*  0 */
                btCommand += infoY + BT_DELIM_DATA; /*  1 */
                btCommand += infoZ + BT_DELIM_DATA; /*  2 */
                btCommand += buttonsPressed + BT_DELIM_DATA; /*  3 */
                btCommand += editText + BT_DELIM_DATA; /*  4 */
                btCommand += t_amountX + BT_DELIM_DATA; /*  5 */
                btCommand += t_amountY + BT_DELIM_DATA; /*  6 */
                btCommand += screenWidth + BT_DELIM_DATA; /*  7 */
                btCommand += screenHeight + BT_DELIM_DATA; /*  8 */
                btCommand += mouseButtonsPressed + BT_DELIM_DATA; /*  9 */
                btCommand += t_scale + BT_DELIM_DATA; /* 10 */
                btCommand += layoutSelected + BT_DELIM_DATA; /* 11 */
                btCommand += btAdapter.getAddress() + BT_DELIM_DATA; /* 12 */
                btCommand += allSensors + BT_DELIM_DATA; /* 13 */
                btCommand += "\n";
                sendData(btCommand);
                buttonsPressed = "";
                mouseButtonsPressed = "";
                editText = "";
                break;
            case Sensor.TYPE_GRAVITY: // not supported
                infoX = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_X]);
                infoY = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Y]);
                infoZ = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Z]);
                sensorsData[1] = new String[]{infoX, infoY, infoZ};
                break;
            case Sensor.TYPE_ORIENTATION: // not supported
                infoX = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_X]);
                infoY = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Y]);
                infoZ = String.format(Locale.ENGLISH, "%1.3f", event.values[SensorManager.DATA_Z]);
                sensorsData[2] = new String[]{infoX, infoY, infoZ};
                break;
            default:
                // TODO: add support for more sensors
                break;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO: implement zoom and drag
        if (layoutSelected == CurrentLayout.STANDARD) {
            return super.onTouchEvent(event);
        } else if (layoutSelected == CurrentLayout.MOUSE) {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    touchMode = TouchMode.DOWN;
                    touchFromX = event.getX();
                    touchFromY = event.getY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    touchOldDist = motionDistance(event);
                    if (touchOldDist > 3f) {
                        touchMode = TouchMode.MOVE;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (touchMode == TouchMode.MOVE) {
                        float newDist = motionDistance(event);
                        if (newDist > 3f) {
                            touchScale = newDist / touchOldDist;
                        }
                    } else if (touchMode == TouchMode.DOWN) {
                        float toX = event.getX();
                        float toY = event.getY();
                        touchDeltaX = toX - touchFromX;
                        touchDeltaY = toY - touchFromY;
                        touchFromX = toX;
                        touchFromY = toY;
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (touchMode == TouchMode.DOWN) {
                        float toX = event.getX();
                        float toY = event.getY();
                        touchDeltaX = toX - touchFromX;
                        touchDeltaY = toY - touchFromY;
                    }
                    touchMode = TouchMode.NONE;
                    return true;
                default:
                    break;
            }
        } else if (layoutSelected == CurrentLayout.MOUSE_WHEEL) {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    touchMode = TouchMode.DOWN;
                    touchFromX = event.getX();
                    touchFromY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float toX = event.getX();
                    float toY = event.getY();
                    touchDeltaX = toX - touchFromX;
                    touchDeltaY = toY - touchFromY;
                    touchFromX = toX;
                    touchFromY = toY;
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    toX = event.getX();
                    toY = event.getY();
                    touchDeltaX = toX - touchFromX;
                    touchDeltaY = toY - touchFromY;
                    touchMode = TouchMode.NONE;
                    return true;
                default:
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void sendData(String message) {
        if (bluetoothOn) {
            byte[] msgBuffer = message.getBytes();
            try {
                outStream.write(msgBuffer);
            } catch (IOException e) {
                errorExit("Fatal Error", e.getMessage());
            }
        }
    }

    private void refreshButtons() {
        commands = new String[22];
        try {
            FileReader reader = new FileReader(new File(getExternalFilesDir(null), "options.txt"));
            BufferedReader br = new BufferedReader(reader);
            for (int i = 0; i < 11; ++i) {
                String[] val = br.readLine().split(",");
                if (val[0].charAt(0) == '"' && val[0].charAt(val[0].length() - 1) == '"') {
                    val[0] = val[0].substring(1, val[0].length() - 1);
                }
                if (val[1].charAt(0) == '"' && val[1].charAt(val[1].length() - 1) == '"') {
                    val[1] = val[1].substring(1, val[1].length() - 1);
                }
                commands[i * 2] = val[0].trim();
                commands[i * 2 + 1] = val[1].trim();
            }
            br.close();
            reader.close();
        } catch (Exception e) {
            try {
                File file = new File(getExternalFilesDir(null), "options.txt");
                FileWriter fw = new FileWriter(file);
                fw.write("\"\",\"\"\r\n\"UP\",\"UP\"\r\n\"\",\"\"\r\n\"LEFT\",\"LEFT\"\r\n\"\",\"\"\r\n" +
                        "\"RIGHT\",\"RIGHT\"\r\n\"\",\"\"\r\n\"DOWN\",\"DOWN\"\r\n\"\",\"\"\r\n" +
                        "\"Menu\",\"F10\"\r\n\"OK\",\"ENTER\"");
                fw.close();
                commands = new String[]{
                        "", "", "UP", "UP", "", "",
                        "LEFT", "LEFT", "", "", "RIGHT", "RIGHT",
                        "", "", "DOWN", "DOWN", "", "",
                        "Menu", "F10", "OK", "ENTER"};
            } catch (Exception e2) {
                errorExit("Fatal Error", e2.getMessage());
            }
        }

        Button[] buttons = new Button[]{
                (Button) findViewById(R.id.button1),
                (Button) findViewById(R.id.button2),
                (Button) findViewById(R.id.button3),
                (Button) findViewById(R.id.button4),
                (Button) findViewById(R.id.button5),
                (Button) findViewById(R.id.button6),
                (Button) findViewById(R.id.button7),
                (Button) findViewById(R.id.button8),
                (Button) findViewById(R.id.button9),
                (Button) findViewById(R.id.buttonA),
                (Button) findViewById(R.id.buttonB)};

        // Handle buttons
        ViewGroup.LayoutParams params;
        for (int i = 0; i < 11; ++i) {
            params = buttons[i].getLayoutParams();
            if (i < 9) {
                params.width = screenWidth / 3;
            } else {
                params.width = screenWidth / 2;
            }
            buttons[i].setLayoutParams(params);
            if (!commands[i * 2 + 1].equals("")) { // if the button is mapped
                buttons[i].setVisibility(View.VISIBLE);
                buttons[i].setText(commands[i * 2]);
                String numb = String.valueOf(i + 1);
                if (i == 9) {
                    numb = "A";
                } else if (i == 10) {
                    numb = "B";
                }

                final String str = numb + "C" + commands[i * 2] + BT_DELIM_VALUE + commands[i * 2 + 1] + BT_DELIM_COMMAND;
                // example: "2CGo up"[04]"UP"[03] (i==1, commands[2]=="Go up", commands[3]=="UP")
                // This format is not final and will most likely be changed in release!
                buttons[i].setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        buttonsPressed += str;
                    }
                });
            } else {
                buttons[i].setVisibility(View.INVISIBLE);
            }
        }
    }

    private float motionDistance(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void errorExit(String title, String message) {
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }
}