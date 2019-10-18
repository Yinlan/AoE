package com.didi.aoe.runtime.tensorflow.lite;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.didi.aoe.library.api.AoeModelOption;
import com.didi.aoe.library.api.ModelSource;
import com.didi.aoe.library.api.StatusCode;
import com.didi.aoe.library.api.convertor.MultiConvertor;
import com.didi.aoe.library.api.interpreter.InterpreterInitResult;
import com.didi.aoe.library.api.interpreter.OnInterpreterInitListener;
import com.didi.aoe.library.api.interpreter.SingleInterpreterComponent;
import com.didi.aoe.library.logging.Logger;
import com.didi.aoe.library.logging.LoggerFactory;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * 提供单模型的TensorFlowLite实现，用于多输入、多输出方式调用。
 *
 * @param <TInput>       范型，业务输入数据
 * @param <TOutput>      范型，业务输出数据
 * @param <TModelInput>  范型，模型输入数据
 * @param <TModelOutput> 范型，模型输出数据
 * @author noctis
 */
public abstract class TensorFlowLiteMultipleInputsOutputsInterpreter<TInput, TOutput, TModelInput, TModelOutput>
        extends SingleInterpreterComponent<TInput, TOutput> implements MultiConvertor<TInput, TOutput, Object, TModelOutput> {
    private final Logger mLogger = LoggerFactory.getLogger("TensorFlowLite.Interpreter");
    private Interpreter mInterpreter;
    private Map<Integer, Object> outputPlaceholder;

    @Override
    public void init(@NonNull Context context, @NonNull AoeModelOption modelOptions, @Nullable OnInterpreterInitListener listener) {

        @ModelSource String modelSource = modelOptions.getModelSource();
        ByteBuffer bb = null;
        if (ModelSource.CLOUD.equals(modelSource)) {
            String modelFilePath = modelOptions.getModelDir() + "_" + modelOptions.getVersion() + File.separator + modelOptions.getModelName();
            File modelFile = new File(FileUtils.prepareRootPath(context) + File.separator + modelFilePath);
            if (modelFile.exists()) {
                try {
                    bb = loadFromExternal(context, modelFilePath);
                } catch (Exception e) {
                    mLogger.warn("IOException", e);
                }
            } else {
                // 配置为云端模型，本地无文件，返回等待中状态
                if (listener != null) {
                    listener.onInitResult(InterpreterInitResult.create(StatusCode.STATUS_MODEL_DOWNLOAD_WAITING));
                }
                return;
            }


        } else {
            String modelFilePath = modelOptions.getModelDir() + File.separator + modelOptions.getModelName();
            // local default
            bb = loadFromAssets(context, modelFilePath);
        }

        if (bb != null) {
            mInterpreter = new Interpreter(bb);

            outputPlaceholder = generalOutputPlaceholder(mInterpreter);
            if (listener != null) {
                listener.onInitResult(InterpreterInitResult.create(StatusCode.STATUS_OK));
            }
            return;
        } else {
            if (listener != null) {
                listener.onInitResult(InterpreterInitResult.create(StatusCode.STATUS_INNER_ERROR));
            }
        }
    }

    private ByteBuffer loadFromExternal(Context context, String modelFilePath) throws IOException {
        FileInputStream fis = new FileInputStream(FileUtils.prepareRootPath(context) + File.separator + modelFilePath);
        FileChannel fileChannel = fis.getChannel();
        long startOffset = fileChannel.position();
        long declaredLength = fileChannel.size();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private Map<Integer, Object> generalOutputPlaceholder(@NonNull Interpreter interpreter) {
        Map<Integer, Object> out = new HashMap<>(interpreter.getOutputTensorCount());
        for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
            Tensor tensor = interpreter.getOutputTensor(i);
            Object data = null;
            switch (tensor.dataType()) {
                case FLOAT32:
                    data = Array.newInstance(Float.TYPE, tensor.shape());
                    break;
                case INT32:
                    data = Array.newInstance(Integer.TYPE, tensor.shape());
                    break;
                case UINT8:
                    data = Array.newInstance(Byte.TYPE, tensor.shape());
                    break;
                case INT64:
                    data = Array.newInstance(Long.TYPE, tensor.shape());
                    break;
                case STRING:
                    data = Array.newInstance(String.class, tensor.shape());
                    break;
                default:
                    // ignore
                    break;
            }
            out.put(i, data);
        }

        return out;
    }

    @Override
    @Nullable
    public TOutput run(@NonNull TInput input) {
        if (isReady()) {
            Object[] modelInput = preProcessMulti(input);

            if (modelInput != null) {

                mInterpreter.runForMultipleInputsOutputs(modelInput, outputPlaceholder);

                //noinspection unchecked
                return postProcessMulti((Map<Integer, TModelOutput>) outputPlaceholder);
            }

        }
        return null;
    }

    @Override
    public void release() {
        if (mInterpreter != null) {
            mInterpreter.close();
        }
    }

    @Override
    public boolean isReady() {
        return mInterpreter != null && outputPlaceholder != null;
    }

    private ByteBuffer loadFromAssets(Context context, String modelFilePath) {
        InputStream is = null;
        try {
            is = context.getAssets().open(modelFilePath);
            byte[] bytes = read(is);
            if (bytes == null) {
                return null;
            }
            ByteBuffer bf = ByteBuffer.allocateDirect(bytes.length);
            bf.order(ByteOrder.nativeOrder());
            bf.put(bytes);

            return bf;
        } catch (IOException e) {
            mLogger.error("loadFromAssets error", e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        return null;

    }

    private byte[] read(InputStream is) {
        BufferedInputStream bis = null;
        ByteArrayOutputStream baos = null;

        try {
            bis = new BufferedInputStream(is);
            baos = new ByteArrayOutputStream();

            int len;

            byte[] buf = new byte[1024];

            while ((len = bis.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }

            return baos.toByteArray();

        } catch (Exception e) {
            mLogger.error("read InputStream error: ", e);
        } finally {
            if (baos != null) {
                try {
                    baos.close();
                } catch (IOException ignored) {
                }
            }
            if (bis != null) {
                try {
                    bis.close();
                } catch (IOException ignored) {
                }
            }
        }
        return new byte[0];
    }
}
