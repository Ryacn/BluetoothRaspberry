package com.radiantkey.bluetoothraspberry;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.stfalcon.imageviewer.StfalconImageViewer;
import com.stfalcon.imageviewer.loader.ImageLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice = null;

    Handler handler;

    RecyclerView recyclerView;
    Button button;
    FloatingActionButton floatingActionButton;

    private SensorManager senSensorManager;
    private Sensor senAccelerometer;

    private long lastUpdate = 0;
    private float last_x, last_y, last_z;
    private static final int SHAKE_THRESHOLD = 600;
    private long cool_time = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        senSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        senAccelerometer = senSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        senSensorManager.registerListener(this, senAccelerometer , SensorManager.SENSOR_DELAY_NORMAL);

        handler = new Handler();

        recyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getApplicationContext());
        recyclerView.setLayoutManager(linearLayoutManager);
        button = (Button) findViewById(R.id.button);
        floatingActionButton = (FloatingActionButton) findViewById(R.id.floatingActionButton);

        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 0);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("raspberrypi")) //Note, you will need to change this to match the name of your device
                {
                    Log.e("RaspCam", device.getName());
                    mmDevice = device;
                    break;
                }
            }
        }

        new Thread(new StringWorker("get_files_dir")).start();

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new StringWorker("get_files_dir")).start();
            }
        });

        floatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new StringWorker("take_picture")).start();
            }
        });
    }

    public void sendStringRequrest(String msg2send) {
        //UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); //Standard SerialPortService ID
        UUID uuid = UUID.fromString("94f39d29-7d6d-437d-973b-fba39e49d4ee"); //Standard SerialPortService ID
        try {

            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            if (!mmSocket.isConnected()) {
                mmSocket.connect();
            }

            String msg = msg2send;
            //msg += "\n";
            OutputStream mmOutputStream = mmSocket.getOutputStream();
            mmOutputStream.write(msg.getBytes());

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    class StringWorker implements Runnable {

        private String btMsg;
        final byte delimiter = 33;
        int readBufferPosition = 0;

        public StringWorker(String msg) {
            btMsg = msg;
        }

        public void run() {
            sendStringRequrest(btMsg);
            while (!Thread.currentThread().isInterrupted()) {
                int bytesAvailable;
                boolean workDone = false;

                try {
                    InputStream mmInputStream = mmSocket.getInputStream();

//                        get receive string end with ! mark
                    bytesAvailable = mmInputStream.available();
                    if (bytesAvailable > 0) {

                        byte[] packetBytes = new byte[bytesAvailable];
//                        Log.e("String worker recv bt", "bytes available");
                        byte[] readBuffer = new byte[1024];
                        mmInputStream.read(packetBytes);

                        for (int i = 0; i < bytesAvailable; i++) {
                            byte b = packetBytes[i];
                            if (b == delimiter) {
                                byte[] encodedBytes = new byte[readBufferPosition];
                                System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                final String data = new String(encodedBytes, "US-ASCII");
                                readBufferPosition = 0;

                                //The variable data now contains our full command
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if(btMsg == "get_files_dir"){
                                            ArrayList<String> file_names = new ArrayList<>();
                                            ArrayList<String> file_sizes = new ArrayList<>();
                                            for(String file : data.split(",")){
                                                String[] file_info = file.split(" ");
                                                file_names.add(file_info[0]);
                                                file_sizes.add(file_info[1]);
                                            }
                                            recyclerView.setAdapter(new CustomAdapter(MainActivity.this, file_names,file_sizes));
                                            Toast.makeText(MainActivity.this, "Reloading List", Toast.LENGTH_SHORT).show();
                                        }else{
                                            Toast.makeText(MainActivity.this, data, Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });

                                workDone = true;
                                break;


                            } else {
                                readBuffer[readBufferPosition++] = b;
                            }
                        }

                        if (workDone == true) {
                            mmSocket.close();
                            break;
                        }

                    }
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

            }
        }
    }

    class FileWorker implements Runnable {

        private String btMsg;
        private String file_size;

        public FileWorker(String msg, String file_size) {
            btMsg = msg;
            this.file_size = file_size;
        }

        public void run() {
            sendStringRequrest(btMsg);
            while (!Thread.currentThread().isInterrupted()) {
                boolean workDone = false;

                try {
                    final int file_size_val = Integer.valueOf(file_size);
                    final byte[] image_bytes = new byte[file_size_val];

                    InputStream mmInputStream = mmSocket.getInputStream();
                    final byte[] buffer = new byte[8192];
                    int byteNo;
                    int currentSize = 0;
                    while((byteNo = mmInputStream.read(buffer)) != -1){
                        System.arraycopy(buffer, 0, image_bytes, currentSize, byteNo);
                        currentSize += byteNo;
                        if(currentSize >= file_size_val)
                            break;
                    }
//                    Log.e("Download:", "" + currentSize + " Bytes downloaded");
                    workDone = true;

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Bitmap bm1 = BitmapFactory.decodeByteArray(image_bytes, 0, file_size_val);
                            new StfalconImageViewer.Builder<>(MainActivity.this, new Bitmap[]{bm1}, new ImageLoader<Bitmap>() {
                                @Override
                                public void loadImage(ImageView imageView, Bitmap image) {
                                    imageView.setImageBitmap(image);
                                }
                            }).show();
                        }
                    });
                    if (workDone == true) {
                        mmSocket.close();
                        break;
                    }
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                    Log.e("error occurred", "IOException");
                    break;
                }

            }
        }
    }

    class CustomAdapter extends RecyclerView.Adapter{

        Context context;
        ArrayList<String> file_names;
        ArrayList<String> file_sizes;

        public CustomAdapter(Context context, ArrayList<String> file_names, ArrayList<String> file_sizes) {
            this.context = context;
            this.file_names = file_names;
            this.file_sizes = file_sizes;
        }

        @NonNull
        @Override
        public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.recycler_layout, parent, false);
            MyViewHolder vh = new MyViewHolder(v);
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, final int position) {
            ((MyViewHolder) holder).file_name.setText(file_names.get(position));
            ((MyViewHolder) holder).file_size.setText(file_sizes.get(position));

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Toast.makeText(context, file_names.get(position), Toast.LENGTH_SHORT).show();
                    new Thread(new FileWorker(file_names.get(position), file_sizes.get(position))).start();
                }
            });
        }

        @Override
        public int getItemCount() {
            return file_names.size();
        }

        public class MyViewHolder extends RecyclerView.ViewHolder{
            TextView file_name, file_size;
            public MyViewHolder(@NonNull View itemView) {
                super(itemView);
                file_name = (TextView) itemView.findViewById(R.id.name);
                file_size = (TextView) itemView.findViewById(R.id.size);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        Sensor mySensor = sensorEvent.sensor;

        if (mySensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = sensorEvent.values[0];
            float y = sensorEvent.values[1];
            float z = sensorEvent.values[2];

            long curTime = System.currentTimeMillis();

            if ((curTime - lastUpdate) > 100) {
                long diffTime = (curTime - lastUpdate);
                lastUpdate = curTime;

                float speed = Math.abs(x + y + z - last_x - last_y - last_z)/ diffTime * 10000;


                if(curTime - cool_time > 5000) {
                    cool_time = curTime;
                    if (speed > SHAKE_THRESHOLD) {
//                        Toast.makeText(MainActivity.this, "shake! " + speed, Toast.LENGTH_SHORT).show();
                        new Thread(new StringWorker("take_picture")).start();
                    }
                }

                last_x = x;
                last_y = y;
                last_z = z;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    protected void onPause() {
        super.onPause();
        senSensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        senSensorManager.registerListener(this, senAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }
}
