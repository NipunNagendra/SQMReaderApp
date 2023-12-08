package com.example.myapplication;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.icu.text.SimpleDateFormat;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.Manifest;
import android.widget.Toast;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
public class MainActivity extends Activity implements View.OnClickListener {

    private UsbSerialPort usbSerialPort;
    private TextView outputTextView;
    private EditText periodTimeEditText;
    private EditText timeLimitEditText;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private volatile boolean breakBool=false;
    private static double latitude;
    private static double longitude;
    private static String sqmReading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button readButton = findViewById(R.id.readButton);
        Button writeButton = findViewById(R.id.writeButton);
        Button getLocationButton = findViewById(R.id.getLocationButton);
        Button startReadingButton = findViewById(R.id.startReadingButton);
        Button stopReadingButton = findViewById(R.id.stopReadingButton);
        Button clearOutputButton = findViewById(R.id.clearOutputButton);
        periodTimeEditText = findViewById(R.id.periodTimeEditText);
        timeLimitEditText = findViewById(R.id.timeLimitEditText);
        outputTextView = findViewById(R.id.outputTextView);

        outputTextView.setMovementMethod(new ScrollingMovementMethod());

        readButton.setOnClickListener(this);
        writeButton.setOnClickListener(this);
        getLocationButton.setOnClickListener(this);
        startReadingButton.setOnClickListener(this);
        stopReadingButton.setOnClickListener(this);
        clearOutputButton.setOnClickListener(this);

        initUsbConnection();
    }

    private void initUsbConnection() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        if (!availableDrivers.isEmpty()) {
            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());

            if (connection != null) {
                usbSerialPort = driver.getPorts().get(0);

                try {
                    usbSerialPort.open(connection);
                    usbSerialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.readButton:
                // Perform read operation
                readFromSerial();
                break;

            case R.id.writeButton:
                // Perform write operation
                writeToSerial("rx");
                break;

            case R.id.getLocationButton:
                requestLocationPermission();
                break;
            case R.id.startReadingButton:
                startReading(Long.parseLong(periodTimeEditText.getText().toString()), Long.parseLong(timeLimitEditText.getText().toString()));
                break;
            case R.id.stopReadingButton:
                stopReading();
                break;
            case R.id.clearOutputButton:
                clearOutput();
        }
    }

    private void startReading(long periodTime, long timeLimit){
        long startTime = System.currentTimeMillis();
        long endTime = startTime + timeLimit*1000;
        if(periodTime == 0){
            periodTime = 1000;
        }
        long period_Length = periodTime;
        breakBool = true;
        Thread t = new Thread(){
            @Override
            public void run() {
            try {
                while (breakBool && System.currentTimeMillis() < endTime) {
                    runOnUiThread(() -> {
                        writeToSerial("rx");
                        readFromSerial();
                        requestLocationPermission();
                        saveToCSV(latitude, longitude, sqmReading);
                    });
                    try {
                        Thread.sleep(period_Length * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }};
        t.start();
    }

    private void stopReading(){
        showToast("Stopped Reading");
        breakBool = false;
    }
    private void readFromSerial() {
        if (usbSerialPort != null) {
            byte[] buffer = new byte[1024];
            try {
                int bytesRead = usbSerialPort.read(buffer, 1000);
                String data = new String(buffer, 0, bytesRead).trim();
                synchronized (this) {
                    if (!data.isEmpty()) {
                        sqmReading = data;
                        runOnUiThread(() -> displayOutput(data));
                    } else {
                        System.err.println("Empty or unexpected data received.");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        } else {
            getLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLocation();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }


    @SuppressLint("MissingPermission")
    private void getLocation() {
        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    synchronized (this){
                        if (location != null) {
                            latitude = location.getLatitude();
                            longitude = location.getLongitude();
                            runOnUiThread(() -> displayOutput( "Latitude: "+ latitude + "\nLongitude: " + longitude));
                        } else {
                            showToast("Location not available");
                    }}
                })
                .addOnFailureListener(e -> {
                    showToast("Error getting location");
                });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }


    private void writeToSerial(String data) {
        synchronized (this){
            if (usbSerialPort != null) {
                try {
                    usbSerialPort.write(data.getBytes(), 1000);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void displayOutput(String data) {
        outputTextView.append(data + "\n");
    }
    private void clearOutput() {
        outputTextView.setText("");
    }

    private void saveToCSV(Double latitude, Double longitude, String sqmReading) {
    String csvFileName = "data.csv";
    synchronized (this) {
        try {
            FileWriter writer = new FileWriter(getExternalFilesDir(null) + "/" + csvFileName, true);
            String date = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            }
            writer.append(date).append(",")
                    .append(String.valueOf(latitude)).append(",")
                    .append(String.valueOf(longitude)).append(",")
                    .append(sqmReading).append("\n");
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
