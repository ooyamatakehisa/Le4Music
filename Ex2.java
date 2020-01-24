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
import javax.sound.sampled.AudioInputStream;
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
import javafx.scene.chart.LineChart;
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

        // ノートナンバーから音程名を表示させる時用のhashMap
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

        String lyrics[] = {
            "新宿は豪雨 あなた何処へやら",
            "今日が 青く冷えてゆく 東京",

            "戦略は皆無 わたし何処へやら",
            "脳が 水滴を奪って乾く",
            "",

            "「泣きたい気持ちは 連なって冬に",
            "雨を齎している」と、云うと",

            "疑わぬあなた「嘘だって好くて",
            "沢山の矛盾が丁度善い」と",

            "答にならぬ”高い無料の論理”で",
            "嘘を嘘だといなすことで即刻",
            "関係の無いヒトとなる",

            "演技をしているんだ",
            "あなただってきっと",
            "そうさ当事者を回避している",

            "興味が湧いたって",
            "据え膳の完成を待って",
            "何とも思わない振りで笑う"
        } ;

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
        final File vocalWav = new File(pargs[0]);
        final File karaokeWav = new File(pargs[1]);
        
        final double duration =
        Optional.ofNullable(cmd.getOptionValue("duration"))
            .map(Double::parseDouble)
            .orElse(Le4MusicUtils.spectrogramDuration);
        final double interval =
        Optional.ofNullable(cmd.getOptionValue("interval"))
            .map(Double::parseDouble)
            .orElse(Le4MusicUtils.frameInterval);

        /* Player を作成 */
        final Player.Builder builder = Player.builder(karaokeWav);
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


        // chrat1に表示する録音している音声の基本周波数用のobervablelist
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
        final double[] window = MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);

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
        final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(frames, fftSize2, player.getNyquist());
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);
        chart.setTitle("Spectrogram");
        chart.setAnimated(false);





        // 予めchart2のガイドボーカルの基本周波数は計算しておく
        final AudioInputStream stream = AudioSystem.getAudioInputStream(vocalWav);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final int songLength = waveform.length;
        /* シフトのサンプル数 */
        final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration / 8);
        final int shiftSize = (int) Math.round(shiftDuration * player.getSampleRate());

        /* 基本周波数出す処理 */
        final double[] arrayOfFundamentalFreaquency = Le4MusicUtils.sliding(waveform, window, shiftSize)
            .mapToDouble(frame -> {
                double fundamentalFreaquency = calculateFundamentalFreaquency(frame,player.getSampleRate());
                return fundamentalFreaquency/4;
            }).toArray();
        






        // ここから音程表示用chart2のコード
        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> vocalNoteData =
            IntStream.range(0,1)
                     .mapToObj(i -> new XYChart.Data<Number, Number>(0,0))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));
        final ObservableList<XYChart.Data<Number, Number>> karaokeNoteData =
            IntStream.range(0,1)
                     .mapToObj(i -> new XYChart.Data<Number, Number>(0,0))
                     .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> vocalNoteSeries = new XYChart.Series<>("vocalNote", vocalNoteData);
        final XYChart.Series<Number, Number> karaokeNoteSeries = new XYChart.Series<>("karaokeNote", karaokeNoteData);

        /* 軸を作成 */
        final NumberAxis xAxis2 = new NumberAxis(
        /* axisLabel  = */ "Time (seconds)",
        /* lowerBound = */ -duration,
        /* upperBound = */ 0,
        /* tickUnit   = */ Le4MusicUtils.autoTickUnit(duration)
        );
        xAxis2.setAnimated(false);

        
        final NumberAxis yAxis2 = new NumberAxis(
        /* axisLabel  = */ "Frequency (Hz)",
        /* lowerBound = */ 100,
        /* upperBound = */ 500,
        /* tickUnit   = */ Le4MusicUtils.autoTickUnit(13 + 1)
        );
        yAxis.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart2 = new LineChart<>(xAxis2, yAxis2);
        chart2.setTitle("vocalNote");
        chart2.setCreateSymbols(false);
        chart2.setLegendVisible(false);
        chart2.getData().add(vocalNoteSeries);
        chart2.getData().add(karaokeNoteSeries);
        chart2.setAnimated(false);






        // ここからスペクトラム表示用のコード
        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> spectrumData =
            IntStream.range(0, 0).mapToObj(i -> new XYChart.Data<Number, Number>(0,0)).collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> spectrumSeries = new XYChart.Series<>("spectrum", spectrumData);

        /* X 軸を作成*/
        
        if (freqLowerBound < 0.0)
            throw new IllegalArgumentException(
                "freq-lo must be non-negative: " + freqLowerBound
            );
        
        if (freqUpperBound <= freqLowerBound)
            throw new IllegalArgumentException(
                "freq-up must be larger than freq-lo: " +
                "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
            );
        final NumberAxis xAxis3 = new NumberAxis(
            /* axisLabel = */ "Frequency (Hz)",
            /* lowerBound = */ freqLowerBound,
            /* upperBound = */ freqUpperBound,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound)
        );
        xAxis3.setAnimated(false);

        /* Y 軸を作成*/
        final double ampLowerBound =
            Optional.ofNullable(cmd.getOptionValue("amp-lo"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeLowerBound);
        final double ampUpperBound =
            Optional.ofNullable(cmd.getOptionValue("amp-up"))
                    .map(Double::parseDouble)
                    .orElse(Le4MusicUtils.spectrumAmplitudeUpperBound);
        if (ampUpperBound <= ampLowerBound)
            throw new IllegalArgumentException(
                "amp-up must be larger than amp-lo: " +
                "amp-lo = " + ampLowerBound + ", amp-up = " + ampUpperBound
            );
        final NumberAxis yAxis3 = new NumberAxis(
            /* axisLabel = */ "Amplitude (dB)",

            /* lowerBound = */ -10,
            /* upperBound = */ ampUpperBound,
            /* tickUnit = */ Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis3.setAnimated(false);

        /* チャートを作成*/
        final LineChart<Number, Number> chart3 =
            new LineChart<>(xAxis3, yAxis3);
        chart3.setTitle("Spectrum");
        chart3.setCreateSymbols(false);
        chart3.setLegendVisible(false);
        chart3.getData().add(spectrumSeries);
        chart3.setAnimated(false);
        spectrumSeries.nodeProperty().get().setStyle("-fx-stroke:red;");
        spectrumSeries.nodeProperty().get().setStyle("-fx-stroke-width:1px;");




        // 表示するテキストそれぞれの初期化
        Label positionText = new Label("Time[s] : "); 
        Label positionValue = new Label("0"); 

        Label freaquencyText = new Label("freaquency[Hz] : "); 
        Label freaquencyValue = new Label("0"); 

        Label noteText = new Label("Note : "); 
        Label noteValue = new Label("0"); 

        Label scoreText = new Label("Score : "); 
        Label scoreValue = new Label("0"); 

        Label lyricsText = new Label(""); 
        lyricsText.setFont(new Font(20));


        // 表示するレイアウトのグリッド感のギャップなどの細かい設定
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
        gridPane.add(chart2,4,0,1,1); 
        gridPane.add(chart3,4,1,1,5); 

        gridPane.add(positionText, 1, 1,1,1); 
        gridPane.add(positionValue, 2, 1,1,1); 

        gridPane.add(freaquencyText, 1, 2,1,1); 
        gridPane.add(freaquencyValue, 2, 2,1,1);

        gridPane.add(noteText, 1, 3,1,1); 
        gridPane.add(noteValue, 2, 3,1,1); 

        gridPane.add(scoreText, 1, 4,1,1); 
        gridPane.add(scoreValue, 2, 4,1,1); 

        gridPane.add(lyricsText, 0, 5,4,1); 

        GridPane.setHalignment(chart, HPos.CENTER);
        GridPane.setHalignment(positionText, HPos.RIGHT);
        GridPane.setHalignment(freaquencyText, HPos.RIGHT);
        GridPane.setHalignment(noteText, HPos.RIGHT);
        
        GridPane.setHalignment(scoreText, HPos.RIGHT);
        GridPane.setHalignment(positionValue, HPos.LEFT);
        GridPane.setHalignment(freaquencyValue, HPos.LEFT);
        GridPane.setHalignment(noteValue, HPos.LEFT);

        GridPane.setHalignment(lyricsText, HPos.CENTER);
        


        /* グラフ描画 */
        final Scene scene = new Scene(gridPane);
        scene.getStylesheets().add("src/le4music.css");
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        /* ウインドウを閉じたときに他スレッドも停止させる */
        primaryStage.setOnCloseRequest(req -> executor.shutdown());
        primaryStage.show();
        Platform.setImplicitExit(true);


        

        // カラオケ流す用のプレイヤー
        player.addAudioFrameListener((frame, position) -> Platform.runLater(() -> {
            final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
            final double logRms = 20.0 * Math.log10(rms);
            final double[] wframe = MathArrays.ebeMultiply(frame, window);
            final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
            final double posInSec = position / player.getSampleRate();

            /* スペクトログラム描画 */
            chart.addSpectrum(spectrum);

            // 配列外参照を起こさない範囲で上で導出したガイドボーカルの基本周波数を描画する。
            // recorderの方の描画と少しずれるのでそこは時間を0.8秒プラスすることでうたったものの基本周波数のタイミングと合わせる。
            if(position/shiftSize<arrayOfFundamentalFreaquency.length){ karaokeNoteData.add( new XYChart.Data<Number, Number>(posInSec+0.8, arrayOfFundamentalFreaquency[position/shiftSize])); }
            

            

            /* 軸を更新 */
            xAxis.setUpperBound(posInSec);
            xAxis.setLowerBound(posInSec - duration);
        }));

        /* 録音開始 */
        Platform.runLater(player::start);


        // 採点用の配列
        final int[] allFrameNumber = new int[]{0};
        final int[] correctAnsNumber = new int[]{0};


        Recorder recorder = Recorder.builder()
                             .mixer(AudioSystem.getMixerInfo()[4])
                             .daemon()
                             .build();
        recorder.addAudioFrameListener((frame, position) -> Platform.runLater(()->{
            final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
            final double logRms = 20.0 * Math.log10(rms);
            final double posInSec = position / recorder.getSampleRate();
            
            /* 軸を更新 */
            xAxis.setUpperBound(posInSec);
            xAxis.setLowerBound(posInSec - duration);
            xAxis2.setUpperBound(posInSec);
            xAxis2.setLowerBound(posInSec - duration);

            int frameSize = recorder.getFrameSize();
            double fundamentalFreaquency = 0;
            int noteNumber = 0;

            // 歌っていない時を下のifで判断している。歌っていないときは歌ったものの基本周波数も0にし、採点の判断にも入れない。
            if(logRms>-100){ 
                fundamentalFreaquency = calculateFundamentalFreaquency(MathArrays.ebeMultiply(frame, window),recorder.getSampleRate());
                noteNumber =  1+( (int)Le4MusicUtils.hz2nn(fundamentalFreaquency)) % 12;

                // 採点用の処理、ここで全フレームをカウントするallFrameNumberを毎回インクリメントし、歌ったものがガイドボーカルの基本周波数に近いときはcorrectAnsNumberもインクリメントする。
                if(arrayOfFundamentalFreaquency.length>position/shiftSize){ 
                    allFrameNumber[0]++;
                    if(fundamentalFreaquency<arrayOfFundamentalFreaquency[position/shiftSize]+50 && fundamentalFreaquency>arrayOfFundamentalFreaquency[position/shiftSize]-50){ correctAnsNumber[0]++;} 
                }   
            }

            // 最終的に採点結果は(歌っていたときの)全フレーム数に対するガイドボーカルの基本周波数との誤差が50Hz未満だったフレーム数の割合で定義した。
            if(arrayOfFundamentalFreaquency.length<position/shiftSize){ 
                double frnum = (double) allFrameNumber[0];
                double crnum = (double) correctAnsNumber[0];

                double score =  100.*crnum/frnum;
                scoreValue.setText(String.valueOf(score*1.5));
            }
            
            
            
            if(posInSec - duration>0){
                data.remove(0,1);
                vocalNoteData.remove(0,1);
            }
            // chart1のスペクトログラム上の基本周波数描画
            data.add( new XYChart.Data<Number, Number>(posInSec, fundamentalFreaquency));
            // chart2の音程のデータ追加
            vocalNoteData.add( new XYChart.Data<Number, Number>(posInSec, fundamentalFreaquency));
           
            // 周波数/音程名の値表示 
            freaquencyValue.setText(String.valueOf(fundamentalFreaquency));
            noteValue.setText(hmap.get(noteNumber));
            


            // 再生位置表示
            BigDecimal bd = new BigDecimal(position/ recorder.getSampleRate() );
            BigDecimal bd2 = bd.setScale(1, BigDecimal.ROUND_DOWN);
            positionValue.setText(bd2.toString());

            lyricsText.setText(setLyrics(position/ recorder.getSampleRate(),lyrics));


            // スペクトラム更新
            double[] spectrum = calculateSpectrum(MathArrays.ebeMultiply(frame, window),recorder.getSampleRate());
            spectrumData.clear();
            spectrumData.addAll(IntStream.range(0,freqs.length)
                .mapToObj(i -> new XYChart.Data<Number, Number>(freqs[i], spectrum[i]))
                .collect(Collectors.toList()));
            
            
        }));
        
        recorder.start();

    }


    // calculate fundamental freaquency 
    public double calculateFundamentalFreaquency(double[] frame,double sampleRate){  
        double ans = 0;
        final double logRms = 20.0 * Math.log10(Arrays.stream(frame).map(x -> x * x).average().orElse(0.0));    
        int zerocrossing = calNumberOfZeroCrossing(Arrays.copyOfRange(frame,0,frame.length));
        // System.out.println(logRms);
        if( zerocrossing<1000 || logRms>-100){ 
            
            final int fftSize = 1 << Le4MusicUtils.nextPow2(frame.length);
            final int fftSize2 = (fftSize >> 1) + 1;
            final double[] src = Arrays.stream(Arrays.copyOf(frame, fftSize)).toArray();
            final Complex[] spectrum = Le4MusicUtils.fft(src);
            final double[] power = Arrays.stream(spectrum).mapToDouble(c -> c.abs()*c.abs()).toArray();
            final Complex[] tmp  = Le4MusicUtils.ifft(power);
            final double[] autocorrelationList = Arrays.stream(tmp).mapToDouble(w -> w.getReal()).toArray();


            // ピークピッキング
            double peakList[] = new double[fftSize];
            int peakIndexList[] = new int[fftSize];
            for(int m=3;m<autocorrelationList.length;m++){
                if((autocorrelationList[m-1]-autocorrelationList[m-2]>=0)&&(autocorrelationList[m]-autocorrelationList[m-1]<0)){
                    peakList[m-3] = autocorrelationList[m-1];
                    peakIndexList[m-3] = m-1;
                }            
            }
            int peakIndex = Le4MusicUtils.argmax(peakList);
            int maxIndex = peakIndexList[peakIndex];
            if(maxIndex==0 || sampleRate/maxIndex>Le4MusicUtils.f0UpperBound+400){ ans =  0;}
            else{ ans = sampleRate/maxIndex;}
        }
        return ans; 
    }



    public double[] calculateSpectrum(double[] frame,double sampleRate){  
        final int fftSize = 1 << Le4MusicUtils.nextPow2(frame.length);
        final int fftSize2 = (fftSize >> 1) + 1;
        final double[] src = Arrays.stream(Arrays.copyOf(frame, fftSize)).toArray();
        final Complex[] spectrum = Le4MusicUtils.fft(src);
        final double[] power = Arrays.stream(spectrum).mapToDouble(c -> c.abs()).toArray();
        final double[] a =  Arrays.stream(power).map(i->{
            // System.out.println(i);
            return Math.log10(i);
            }).toArray();
        return a; 
    }

    public int calNumberOfZeroCrossing(double[] waveform){
        int count = 0;
        for(int i=0;i<waveform.length-1;i++){
            if(waveform[i]*waveform[i+1]<0){
                count ++;
            }
        }
        return count;
    }

    public String setLyrics(double time,String[] lyrics){
        if(time < 6.7) return lyrics[0];
        else if(time < 11.7) return lyrics[1];
        else if(time < 18.0) return lyrics[2];
        else if(time < 24.5) return lyrics[3];
        else if(time < 33.5) return lyrics[4];
        else if(time < 39.2) return lyrics[5];
        else if(time < 44.7) return lyrics[6];
        else if(time < 50.0) return lyrics[7];
        else if(time < 55.3) return lyrics[8];
        else if(time < 60.8) return lyrics[9];
        else if(time < 64.9) return lyrics[10];
        else if(time < 70.0) return lyrics[11];
        else if(time < 73.6) return lyrics[12];
        else if(time < 76.0) return lyrics[13];
        else if(time < 80.7) return lyrics[14];
        else if(time < 84.6) return lyrics[15];
        else if(time < 88.0) return lyrics[16];
        else if(time < 91.2) return lyrics[17];
        else  return "";
    }

}
