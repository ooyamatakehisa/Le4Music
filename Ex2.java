import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.HashMap;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.math.BigDecimal;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets; 
import javafx.geometry.Pos;
import javafx.geometry.HPos;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.GridPane; 
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.text.*;
import javafx.scene.control.Label;

import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import jp.ac.kyoto_u.kuis.le4music.CheckAudioSystem;
import jp.ac.kyoto_u.kuis.le4music.Recorder;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class Ex2 extends Application {

  private static final Options options = new Options();
  private static final String helpMessage =
    MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

  static {
    /* コマンドラインオプション定義 */
    options.addOption("h", "help", false, "display this help and exit");
    options.addOption("v", "verbose", false, "Verbose output");
    options.addOption("m", "mixer", true,
                      "Index of the Mixer object that supplies a SourceDataLine object. " +
                      "To check the proper index, use CheckAudioSystem");
    options.addOption("l", "loop", false, "Loop playback");
    options.addOption("f", "frame", true,
                      "Frame duration [seconds] " +
                      "(Default: " + Le4MusicUtils.frameDuration + ")");
    options.addOption("i", "interval", true,
                      "Frame notification interval [seconds] " +
                      "(Default: " + Le4MusicUtils.frameInterval + ")");
    options.addOption("b", "buffer", true,
                      "Duration of line buffer [seconds]");
    options.addOption("d", "duration", true,
                      "Duration of spectrogram [seconds]");
    options.addOption(null, "amp-lo", true,
                      "Lower bound of amplitude [dB] (Default: " +
                      Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
    options.addOption(null, "amp-up", true,
                      "Upper bound of amplitude [dB] (Default: " +
                      Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
    options.addOption(null, "freq-lo", true,
                      "Lower bound of frequency [Hz] (Default: 0.0)");
    options.addOption(null, "freq-up", true,
                      "Upper bound of frequency [Hz] (Default: Nyquist)");
  }

    @Override /* Application */
    public final void start(final Stage primaryStage)
    throws IOException,
         UnsupportedAudioFileException,
         LineUnavailableException,
         ParseException {
        /* コマンドライン引数処理 */
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        final CommandLine cmd = new DefaultParser().parse(options, args);
        if (cmd.hasOption("help")) {
        new HelpFormatter().printHelp(helpMessage, options);
        Platform.exit();
        return;
        }
        verbose = cmd.hasOption("verbose");

        final String[] pargs = cmd.getArgs();
        if (pargs.length < 1) {
        System.out.println("WAVFILE is not given.");
        new HelpFormatter().printHelp(helpMessage, options);
        Platform.exit();
        return;
        }
        final File wavFile = new File(pargs[0]);

        final double duration =
        Optional.ofNullable(cmd.getOptionValue("duration"))
            .map(Double::parseDouble)
            .orElse(Le4MusicUtils.spectrogramDuration);
        final double interval =
        Optional.ofNullable(cmd.getOptionValue("interval"))
            .map(Double::parseDouble)
            .orElse(Le4MusicUtils.frameInterval);

        /* Player を作成 */
        final Player.Builder builder = Player.builder(wavFile);
        Optional.ofNullable(cmd.getOptionValue("mixer"))
        .map(Integer::parseInt)
        .map(index -> AudioSystem.getMixerInfo()[index])
        .ifPresent(builder::mixer);
        if (cmd.hasOption("loop"))
        builder.loop();
        Optional.ofNullable(cmd.getOptionValue("buffer"))
        .map(Double::parseDouble)
        .ifPresent(builder::bufferDuration);
        Optional.ofNullable(cmd.getOptionValue("frame"))
        .map(Double::parseDouble)
        .ifPresent(builder::frameDuration);
        builder.interval(interval);
        builder.daemon();
        final Player player = builder.build();

        /* データ処理スレッド */
        final ExecutorService executor = Executors.newSingleThreadExecutor();


        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, 1)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(0, 0))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Waveform", data);



        /* 窓関数とFFTのサンプル数 */
        final int fftSize = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
        final int fftSize2 = (fftSize >> 1) + 1;

        /* 窓関数を求め，それを正規化する */
        final double[] window =
        MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);

        /* 各フーリエ変換係数に対応する周波数 */
        final double[] freqs =
        IntStream.range(0, fftSize2)
                .mapToDouble(i -> i * player.getSampleRate() / fftSize)
                .toArray();

        /* フレーム数 */
        final int frames = (int)Math.round(duration / interval);

        /* 軸を作成 */
        final NumberAxis xAxis = new NumberAxis(
        /* axisLabel  = */ "Time (seconds)",
        /* lowerBound = */ -duration,
        /* upperBound = */ 0,
        /* tickUnit   = */ Le4MusicUtils.autoTickUnit(duration)
        );
        xAxis.setAnimated(false);

        final double freqLowerBound =
        Optional.ofNullable(cmd.getOptionValue("freq-lo"))
            .map(Double::parseDouble)
            .orElse(0.0);
        if (freqLowerBound < 0.0)
        throw new IllegalArgumentException(
            "freq-lo must be non-negative: " + freqLowerBound
        );
        final double freqUpperBound =
        Optional.ofNullable(cmd.getOptionValue("freq-up"))
            .map(Double::parseDouble)
            .orElse(player.getNyquist());
        if (freqUpperBound <= freqLowerBound)
        throw new IllegalArgumentException(
            "freq-up must be larger than freq-lo: " +
            "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
        );
        final NumberAxis yAxis = new NumberAxis(
        /* axisLabel  = */ "Frequency (Hz)",
        /* lowerBound = */ freqLowerBound,
        /* upperBound = */ 1000,
        /* tickUnit   = */ Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound)
        );
        yAxis.setAnimated(false);

        /* スペクトログラム表示chart */
        final LineChartWithSpectrogram<Number, Number> chart =
        new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(frames, fftSize2, player.getNyquist());
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);
        chart.setTitle("Spectrogram");
        chart.setAnimated(false);



        Label positionText = new Label("Time[s] : "); 
        Label positionValue = new Label("0"); 

        Label freaquencyText = new Label("freaquency[Hz] : "); 
        Label freaquencyValue = new Label("0"); 

        Label noteText = new Label("Note : "); 
        Label noteValue = new Label("0"); 




        GridPane gridPane = new GridPane();    
        gridPane.getColumnConstraints().add(new ColumnConstraints(130)); // column 0 is 100 wide
        gridPane.getColumnConstraints().add(new ColumnConstraints(130)); // column 1 is 100 wide
        gridPane.getColumnConstraints().add(new ColumnConstraints(130)); // column 3 is 100 wide
        gridPane.getColumnConstraints().add(new ColumnConstraints(130)); // column 4 is 100 wide
        //Setting the padding  
        gridPane.setPadding(new Insets(10, 10, 10, 10)); 
        //Setting the vertical and horizontal gaps between the columns 
        gridPane.setVgap(5); 
        gridPane.setHgap(5);       
        //Setting the Grid alignment 
        gridPane.setAlignment(Pos.CENTER); 



        //Arranging all the nodes in the grid 
        gridPane.add(chart, 0, 0,4,1);

        gridPane.add(positionText, 1, 1,1,1); 
        gridPane.add(positionValue, 2, 1,1,1); 

        gridPane.add(freaquencyText, 1, 2,1,1); 
        gridPane.add(freaquencyValue, 2, 2,1,1);

        gridPane.add(noteText, 1, 3,1,1); 
        gridPane.add(noteValue, 2, 3,1,1); 

        GridPane.setHalignment(chart, HPos.CENTER);
        GridPane.setHalignment(positionText, HPos.RIGHT);
        GridPane.setHalignment(freaquencyText, HPos.RIGHT);
        GridPane.setHalignment(noteText, HPos.RIGHT);
        GridPane.setHalignment(positionValue, HPos.LEFT);
        GridPane.setHalignment(freaquencyValue, HPos.LEFT);
        GridPane.setHalignment(noteValue, HPos.LEFT);
        

        /* グラフ描画 */
        final Scene scene = new Scene(gridPane);
        scene.getStylesheets().add("src/le4music.css");
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        /* ウインドウを閉じたときに他スレッドも停止させる */
        primaryStage.setOnCloseRequest(req -> executor.shutdown());
        primaryStage.show();
        Platform.setImplicitExit(true);

        // player.addAudioFrameListener((frame, position) -> executor.execute(() -> {
        //     final double[] wframe = MathArrays.ebeMultiply(frame, window);
        //     final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
        //     final double posInSec = position / player.getSampleRate();

        //     /* スペクトログラム描画 */
        //     chart.addSpectrum(spectrum);

        //     /* 軸を更新 */
        //     xAxis.setUpperBound(posInSec);
        //     xAxis.setLowerBound(posInSec - duration);
        // }));

        /* 録音開始 */
        // Platform.runLater(player::start);


        HashMap<Integer, String> hmap = new HashMap<Integer, String>();
        hmap.put(1, "C");
        hmap.put(2, "C#");
        hmap.put(3, "D");
        hmap.put(4, "D#");
        hmap.put(5, "E");
        hmap.put(6, "F");
        hmap.put(7, "F#");
        hmap.put(8, "G");
        hmap.put(9, "G#");
        hmap.put(10, "A");
        hmap.put(11, "A#");
        hmap.put(12, "B");

        try{
            CheckAudioSystem.main(args);

        }catch(javax.sound.sampled.LineUnavailableException e){}

        Recorder recorder = Recorder.builder()
                             .mixer(AudioSystem.getMixerInfo()[4])
                             .daemon()
                             .build();
        recorder.addAudioFrameListener((frame, position) -> {
            final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
            final double logRms = 20.0 * Math.log10(rms);
            final double posInSec = position / recorder.getSampleRate();
            System.out.printf("Position %d (%.2f sec), RMS %f dB%n", position, posInSec, logRms);

            final double[] wframe = MathArrays.ebeMultiply(frame, window);
            final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));

            /* スペクトログラム描画 */
            chart.addSpectrum(spectrum);

            /* 軸を更新 */
            xAxis.setUpperBound(posInSec);
            xAxis.setLowerBound(posInSec - duration);

            int frameSize = recorder.getFrameSize();
            double fundamentalFreaquency = calculateFundamentalFreaquency(frame,frameSize,recorder.getSampleRate());
            
            Platform.runLater(()->{
                if(posInSec - duration>0){
                    data.remove(0,1);
                }
                data.add( new XYChart.Data<Number, Number>(posInSec, fundamentalFreaquency));
                // 周波数の値表示 
                freaquencyValue.setText(String.valueOf(fundamentalFreaquency));

                int noteNumber = ((int) Le4MusicUtils.hz2nn(fundamentalFreaquency) - 21) % 12;
                noteValue.setText(hmap.get(noteNumber));


                // 再生位置表示
                BigDecimal bd = new BigDecimal(position/ recorder.getSampleRate() );
                BigDecimal bd2 = bd.setScale(1, BigDecimal.ROUND_DOWN);
                positionValue.setText(bd2.toString());


            });

            

        });
        
        recorder.start();


    }



    public double calculateFundamentalFreaquency(double[] frame,int frameSize,double sampleRate){
        double autocorrelationList[] = new double[frameSize];
        for(int tau=0;tau<frameSize;tau++){ //全てのタウについて
            double autocorrelation = 0;
            for(int j=0;j<frameSize-tau;j++){ //和を求める用のfor
                autocorrelation += frame[j]*frame[j+tau];
            }
            autocorrelationList[tau] = autocorrelation;
        }
        // あるフレームについて511のAC(自己相関関数)が存在する。各τについて
        
        // ピークピッキング
        
        double peakList[] = new double[frameSize];
        int peakIndexList[] = new int[frameSize];
        for(int m=3;m<autocorrelationList.length;m++){
            if((autocorrelationList[m-1]-autocorrelationList[m-2]>=0)&&(autocorrelationList[m]-autocorrelationList[m-1]<0)){
                peakList[m-3] = autocorrelationList[m-1];
                peakIndexList[m-3] = m-1;
            }            
        }
        int peakIndex = Le4MusicUtils.argmax(peakList);
        int maxIndex = peakIndexList[peakIndex];
        double ans;
        if(maxIndex==0 || sampleRate/maxIndex>Le4MusicUtils.f0UpperBound){ ans =  0;}
        else{ ans = sampleRate/maxIndex;}
        return ans;
    }

}
