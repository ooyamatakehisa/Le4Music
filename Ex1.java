import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import jp.ac.kyoto_u.kuis.le4music.CheckAudioSystem;
import javax.sound.sampled.LineUnavailableException;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.AudioFrameListener;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;


import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class Ex1 extends Application {

    private static final Options options = new Options();
    private static final String helpMessage =
        MethodHandles.lookup().lookupClass().getName()+" [OPTIONS] <WAVFILE>";

    static {
        /* コマンドラインオプション定義*/
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true,
                          "Output image file (Default: " +
                          MethodHandles.lookup().lookupClass().getSimpleName() +
                          "." + Le4MusicUtils.outputImageExt + ")");
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
        options.addOption("v", "verbose", false, "Verbose output");
        options.addOption("m", "mixer", true,
                        "Index of the Mixer object that " +
                        "supplies a SourceDataLine object. " +
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
        options.addOption("a", "amp-bounds", true,
                        "Upper(+) and lower(-) bounds in the amplitude direction " +
                        "(Default: " + Le4MusicUtils.waveformAmplitudeBounds + ")");
    }

    @Override public final void start(final Stage primaryStage)
        throws IOException,
               UnsupportedAudioFileException,
               ParseException {
        /* コマンドライン引数処理*/
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        final CommandLine cmd = new DefaultParser().parse(options, args);
        if (cmd.hasOption("help")) {
            new HelpFormatter().printHelp(helpMessage, options);
            Platform.exit();
            return;
        }
        final String[] pargs = cmd.getArgs();
        if (pargs.length < 1) {
            System.out.println("WAVFILE is not given.");
            new HelpFormatter().printHelp(helpMessage, options);
            Platform.exit();
            return;
        }
        // 引数のぞれぞれのwavファイルを配列に格納/* W A V ファイル読み込み*/
        final File[] wavFileList = new File[6];
        wavFileList[0] = new File("a.wav");
        wavFileList[1] = new File("i.wav");
        wavFileList[2] = new File("u.wav");
        wavFileList[3] = new File("e.wav");
        wavFileList[4] = new File("o.wav");
        wavFileList[5] = new File(pargs[0]);  //認識対象のファイル
        final AudioInputStream[] streamList =  new AudioInputStream[6];
        final double[][] waveformList = new double[6][];
        for(int i=0;i<6;i++){
            streamList[i] = AudioSystem.getAudioInputStream(wavFileList[i]);
            waveformList[i] = Le4MusicUtils.readWaveformMonaural(streamList[i]);
        }



        // final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = streamList[0].getFormat();
        final double sampleRate = format.getSampleRate();
        final double nyquist = sampleRate * 0.5;
        streamList[0].close();

        /* 窓関数とFFTのサンプル数 */
        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration);
        final int frameSize = (int) Math.round(frameDuration * sampleRate);

        
        // これが各フレーム長さ
        int forFrameSize = frameSize/8;
        int hopsize =forFrameSize/2;

        /* fftSize = 2ˆp >= forFrameSize を満たすfftSize を求める
            * 2ˆp はシフト演算で求める*/
        final int fftSize = 1 << Le4MusicUtils.nextPow2(forFrameSize);
        final int fftSize2 = (fftSize >> 1) + 1; //s^(p-1)+1 ??
        // ケプストラム出す際のフーリエ変換するときの引数の長さ
        final int fftSize3 = fftSize2-1;
        final int fftSize4 = (fftSize3 >> 1) + 1; //s^(p-1)+1 ??

        // 何次までのケプストラムをとってきて認識に使うか。「この数*char数」だけ正規分布も作られる。
        int NumOfCepstrum = 13;

        // ここに各dのcepstrumを足していく。
        final double[][] avgCepstrumArray = new double[5][NumOfCepstrum];
        final double[][] varCepstrumArray = new double[5][NumOfCepstrum];     

        
        for(int charNo=0;charNo<5;charNo++){ //それぞれの母音について平均と分散を出す。
            int N = waveformList[charNo].length;
            final Complex[][] cepstrum = new Complex[N-forFrameSize-1][fftSize4];
            int count = 0;
            for(int k=0;k<N-forFrameSize-1;k+=hopsize){ //すべてのフレームについて

                // そのフレームにおける配列,
                final double[] frameArray = Arrays.copyOfRange(waveformList[charNo], k,k+forFrameSize);

                /* 信号の長さをfftSize に伸ばし， 長さが足りない部分は0 で埋める．
                * 振幅を信号長で正規化する． */
                final double[] src =
                    Arrays.stream(Arrays.copyOf(frameArray, fftSize))
                        .map(w -> w / forFrameSize)
                        .toArray();
                /* 高速フーリエ変換を行う*/
                final Complex[] spectrum = Le4MusicUtils.rfft(src); // 2^p -> 2^(p-1)+1


                // ケプストラム求める！

                /* 1.対数振幅スペクトルを求める*/
                final double[] specLog =
                    Arrays.stream(spectrum)
                        .mapToDouble(c -> 20*Math.log10(c.abs()))
                        .toArray();

                /* 1.5 スペクトルの配列の長さを2のn上にする*/
                final double[] normalizeSpecLog = Arrays.copyOfRange(specLog, 0,fftSize3);

                /* 2.対数振幅スペクトルをフーリエ変換する*/
                cepstrum[k] = Le4MusicUtils.rfft(normalizeSpecLog);  //2^n -> 2^(n-1)+1

                // 各フレームのケプストラム(1-13次)の値を足していく
                for(int d=0;d<NumOfCepstrum;d++){ //0<d<14
                    avgCepstrumArray[charNo][d] += cepstrum[k][d].getReal();
                }

                count++;
            }

            // ここで各フレームで足したものの平均を求める
            for(int d=0;d<NumOfCepstrum;d++){
                avgCepstrumArray[charNo][d] /= count;
            }

            // 分散計算用のシグマのためのloop、各フレームの分散？をたしていく
            for(int k=0;k<N-forFrameSize-1;k+=hopsize){ //すべてのフレームについて
                for(int d=0;d<NumOfCepstrum;d++){
                    varCepstrumArray[charNo][d] += Math.pow(cepstrum[k][d].getReal()-avgCepstrumArray[charNo][d],2);
                }
            }
            // 分散てきなやつをたしていってたのの平均をとる。varCepstrumArrayは正確には標準偏差
            for(int d=0;d<NumOfCepstrum;d++){
                varCepstrumArray[charNo][d] = Math.sqrt(varCepstrumArray[charNo][d]/count);
            }
        }
        


        // ここから認識対象のwavをケプストラムに変換する。
        int N = waveformList[5].length;
        int res[] = new int[(N-forFrameSize-1)/hopsize+1];
        int count = 0;
        for(int k=0;k<N-forFrameSize-1;k+=hopsize){ //すべてのフレームについて
            // 正規分布用の関数
            final double[] normalDistribution = new double[5];

            // そのフレームにおける配列,
            final double[] frameArray = Arrays.copyOfRange(waveformList[5], k,k+forFrameSize);
            
            final int fftSizeRcg = 1 << Le4MusicUtils.nextPow2(frameArray.length);
            final int fftSize2Rcg = (fftSize >> 1) + 1; //2^(p-1)+1 ??
            final int fftSize3Rcg = fftSize2Rcg -1;
            final double[] srcRcg =
                Arrays.stream(Arrays.copyOf(frameArray, fftSizeRcg))
                    .map(w -> w / frameArray.length)
                    .toArray();
            /* 高速フーリエ変換を行う*/
            final Complex[] spectrumRcg = Le4MusicUtils.rfft(srcRcg); // 2^p -> 2^(p-1)+1


            // ケプストラム求める！

            /* 1.対数振幅スペクトルを求める*/
            final double[] specLogRcg =
                Arrays.stream(spectrumRcg)
                    .mapToDouble(c -> 20*Math.log10(c.abs()))
                    .toArray();

            /* 1.5 スペクトルの配列の長さを2のn上にする*/
            final double[] normalizeSpecLogRcg = Arrays.copyOfRange(specLogRcg, 0,fftSize3Rcg); // 2^(p-1)+1 -> 2^(p-1)
            
            
            /* 2.対数振幅スペクトルをフーリエ変換する*/
            final Complex[] cepstrumRcg = Le4MusicUtils.rfft(normalizeSpecLogRcg);  //2^n -> 2^(n-1)+1
            
            // ここで学習したmeanとvarianceを用いて正規分布に突っ込み各正規分布でのその値の確率を配列に格納し認識を行う
            for(int charNo=0;charNo<5;charNo++){
                for(int d=0;d<NumOfCepstrum;d++){
                    normalDistribution[charNo] += Math.log(varCepstrumArray[charNo][d])+(Math.pow(cepstrumRcg[d].getReal()-avgCepstrumArray[charNo][d],2))/(2*Math.pow(varCepstrumArray[charNo][d],2));
                }
                normalDistribution[charNo] = -normalDistribution[charNo];
            }

            count++;
            res[k/hopsize] = Le4MusicUtils.argmax(normalDistribution);
            // System.out.println(Le4MusicUtils.argmax(normalDistribution));
        }








        
        /* 窓関数とFFTのサンプル数 */
        final int fftSizeFF = 1 << Le4MusicUtils.nextPow2(frameSize);
        final int fftSizeFF2 = (fftSizeFF >> 1) + 1;

           // 自己相関関数による基本周波数導出
        double autocorrelationList[] = new double[N];
        double ansList[] = new double[N];
        for(int k=0;k<N-forFrameSize-1;k+=hopsize){ //すべてのフレームについて
            for(int tau=0;tau<forFrameSize;tau++){ //全てのタウについて
                double autocorrelation = 0;
                for(int j=k;j<k+forFrameSize-tau;j++){ //和を求める用のfor
                    autocorrelation += waveformList[5][j]*waveformList[5][j+tau];
                }
                autocorrelationList[tau] = autocorrelation;

            }
            // あるフレームについて511のAC(自己相関関数)が存在する。各τについて
            
            // ピークピッキング
          
            double peakList[] = new double[N];
            int peakIndexList[] = new int[N];
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
            ansList[k] = ans;
        }
         /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, N/hopsize)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i*hopsize / sampleRate, ansList[i*hopsize]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Waveform", data);





        // 動かす軸用のデータシリーズ作成
        ArrayList<XYChart.Data<Number, Number>> vertialArray = new  ArrayList<XYChart.Data<Number, Number>>();
        vertialArray.add(new XYChart.Data<Number, Number>(0,0));
        vertialArray.add(new XYChart.Data<Number, Number>(0,2000));
        ObservableList<XYChart.Data<Number, Number>> verticalData = vertialArray.stream().collect(Collectors.toCollection(FXCollections::observableArrayList));
        XYChart.Series<Number, Number> verticalSeries = new XYChart.Series<>("Waveform", verticalData);

        





        // ここから認識対象のスペクトログラム表示
        /* シフトのサンプル数 */
        final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration / 8);
        final int shiftSize = (int) Math.round(shiftDuration * sampleRate);

        /* 窓関数を求め， それを正規化する */
        final double[] window = MathArrays.normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSizeFF), 1.0);

        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveformList[5], window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> 20.0 * Math.log10(c.abs())).toArray())
                .toArray(n -> new double[n][]);


        /* X 軸を作成 */
        final double duration = (specLog.length - 1) * shiftDuration;

        final NumberAxis xAxis = new NumberAxis(/* axisLabel = */ "Time (seconds)", /* lowerBound = */ 0.0,
                /* upperBound = */ duration, /* tickUnit = */ Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);

        /* Y 軸を作成 */
        final NumberAxis yAxis = new NumberAxis(/* axisLabel = */ "Frequency (Hz)", /* lowerBound = */ 0.0,
                /* upperBound = */ /*nyquist*/2000, /* tickUnit = */ Le4MusicUtils.autoTickUnit(nyquist));
        yAxis.setAnimated(false);

        /* チャートを作成 */
        final LineChartWithSpectrogram<Number, Number> chart = new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(specLog.length, fftSizeFF2, nyquist);
        chart.setTitle("Spectrogram");
        Arrays.stream(specLog).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.getData().add(series);
        chart.getData().add(verticalSeries);

        

        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("src/le4music.css");

        /* ウインドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();


        try{
            CheckAudioSystem.main(args);
        }catch(javax.sound.sampled.LineUnavailableException e){}
        


       // 再生関連
        try{
            Player player = Player.builder(wavFileList[5])
                            .mixer(AudioSystem.getMixerInfo()[1])
                            .daemon()
                            .build();
            player.addAudioFrameListener((frame, position) -> {
            final double rms = Arrays.stream(frame).map(x -> x * x).average().orElse(0.0);
            final double logRms = 20.0 * Math.log10(rms);
            final double posInSec = position / player.getSampleRate();
            // System.out.printf("Position %d (%.3f sec), RMS %f dB%n", position, posInSec, logRms);
            });
            player.start();
        }catch(LineUnavailableException e){}
    }

}