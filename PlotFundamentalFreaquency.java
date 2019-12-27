import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.collections.ObservableList;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;

public final class PlotFundamentalFreaquency extends Application {

    private static final Options options = new Options();
    private static final String helpMessage = MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

    static {
        /* コマンドラインオプション定義 */
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true, "Output image file (Default: "
                + MethodHandles.lookup().lookupClass().getSimpleName() + "." + Le4MusicUtils.outputImageExt + ")");
        options.addOption("f", "frame", true,
                "Duration of frame [seconds] (Default: " + Le4MusicUtils.frameDuration + ")");
        options.addOption("s", "shift", true, "Duration of shift [seconds] (Default: frame/8)");
    }

    @Override
    public final void start(final Stage primaryStage)
            throws IOException, UnsupportedAudioFileException, ParseException {
        /* コマンドライン引数処理 */
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

        final File wavFile = new File(pargs[0]);

        /* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        final double nyquist = sampleRate * 0.5;
        stream.close();

        // サンプル周期を出したかったが結局サンプルレートの逆数
        // long length = stream.getFrameLength(); // 総フレーム数の取得。
        // double playtime = length / sampleRate; //曲の長さ
        // double samplePeriod = playtime/waveform.length;

     



        /* 窓関数とFFTのサンプル数 */
        final double frameDuration = Optional.ofNullable(cmd.getOptionValue("frame")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration);
        final int frameSize = (int) Math.round(frameDuration * sampleRate);
        final int fftSize = 1 << Le4MusicUtils.nextPow2(frameSize);
        final int fftSize2 = (fftSize >> 1) + 1;

           // 自己相関関数による基本周波数導出
        int N = waveform.length;
        int forFrameSize = frameSize/8;
        int hopsize =forFrameSize/8;
        double autocorrelationList[] = new double[N];
        double ansList[] = new double[N];
        for(int k=0;k<N-forFrameSize-1;k+=hopsize){ //すべてのフレームについて
            for(int tau=0;tau<forFrameSize;tau++){ //全てのタウについて
                double autocorrelation = 0;
                for(int j=k;j<k+forFrameSize-tau;j++){ //和を求める用のfor
                    autocorrelation += waveform[j]*waveform[j+tau];
                }
                autocorrelationList[tau] = autocorrelation;

            }
            // あるフレームについて511のAC(自己相関関数)が存在する。各τについて
            
            // ピークピッキング
          
            double peakList[] = new double[waveform.length];
            int peakIndexList[] = new int[waveform.length];
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
        }
        /* データ系列を作成*/
        final ObservableList<XYChart.Data<Number, Number>> data =
            IntStream.range(0, N/hopsize)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i*hopsize / sampleRate, ansList[i*hopsize]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));

        /* データ系列に名前をつける*/
        final XYChart.Series<Number, Number> series =
            new XYChart.Series<>("Waveform", data);

        /* シフトのサンプル数 */
        final double shiftDuration = Optional.ofNullable(cmd.getOptionValue("shift")).map(Double::parseDouble)
                .orElse(Le4MusicUtils.frameDuration / 8);
        final int shiftSize = (int) Math.round(shiftDuration * sampleRate);

        /* 窓関数を求め， それを正規化する */
        final double[] window = MathArrays.normalizeArray(Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize),
                1.0);

        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram = Le4MusicUtils.sliding(waveform, window, shiftSize)
                .map(frame -> Le4MusicUtils.rfft(frame));

        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog = spectrogram.map(sp -> Arrays.stream(sp).mapToDouble(c -> 20.0 * Math.log10(c.abs())).toArray())
                .toArray(n -> new double[n][]);

        /* 参考： フレーム数と各フレーム先頭位置の時刻 */
        final double[] times = IntStream.range(0, specLog.length).mapToDouble(i -> i * shiftDuration).toArray();

        /* 参考： 各フーリエ変換係数に対応する周波数 */
        final double[] freqs = IntStream.range(0, fftSize2).mapToDouble(i -> i * sampleRate / fftSize).toArray();

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
        chart.setParameters(specLog.length, fftSize2, nyquist);
        chart.setTitle("Spectrogram");
        Arrays.stream(specLog).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.getData().add(series);

        

        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("src/le4music.css");

        /* ウインドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();

        /* チャートを画像ファイルへ出力 */
        Platform.runLater(() -> {
            final String[] name_ext = Le4MusicUtils.getFilenameWithImageExt(
                    Optional.ofNullable(cmd.getOptionValue("outfile")), getClass().getSimpleName());
            final WritableImage image = scene.snapshot(null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), name_ext[1],
                        new File(name_ext[0] + "." + name_ext[1]));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

}