package com.chiemy.downloadengine;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;

import com.chiemy.downloadengine.db.DownloadInfoDAO;

public final class DownloadEngine<T extends Downloadable> implements
		IDownloadEngine<T>, DownloadTaskListener {
	private static ExecutorService LIMITED_TASK_EXECUTOR;
	private static final int DEFAULT_TASK_NUM = 1;
	static {
		LIMITED_TASK_EXECUTOR = (ExecutorService) Executors
				.newFixedThreadPool(DEFAULT_TASK_NUM);
	}

	private DownloadEngineConfig mConfig;
	private DownloadInfoDAO infoDAO;
	private Map<String, DownloadTask> taskMap;
	private List<DownloadInfo> unfinishedList, finishedList;

	private static final int MSG_TASK = 1;
	private static final int MSG_STATUS_CHANGE = 2;
	private Handler handler = new Handler(Looper.getMainLooper()) {
		@SuppressWarnings("unchecked")
		public void handleMessage(android.os.Message msg) {
			if (msg.what == MSG_TASK) {
				DownloadTask task = (DownloadTask) msg.obj;
				// 开启下载任务
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
					forAPI11(task);
				} else {
					task.execute("");
				}
				onStatusChange(task.getDownloadInfo());
			}else if(msg.what == MSG_STATUS_CHANGE){
				DownloadInfo info = (DownloadInfo) msg.obj;
				onStatusChange(info);
			}
		}
	};

	private String id;
	DownloadEngine(String id, DownloadEngineConfig config) {
		this.id = id;
		mConfig = config;
		infoDAO = DownloadInfoDAO.getInstance(config.context);
		taskMap = new HashMap<String, DownloadTask>();
		mUpdateInterval = mConfig.progressUploadInterval;
		unfinishedList = new ArrayList<DownloadInfo>();
		finishedList = new ArrayList<DownloadInfo>();
		init();
	}
	
	private void init(){
		finishedList.addAll(infoDAO.queryAllFinishedTask(id));
		unfinishedList.addAll(infoDAO.queryAllUnFinishTask(id));
	}

	@Override
	public void start(T entity) {
		if (entity == null) {
			return;
		}
		DownloadInfo info = entity.getDownloadInfo();
		if (info == null) {
			info = queryDownloadInfo(entity);
			if (info == null) {
				info = new DownloadInfo(entity);
				info.setEngineId(id);
				info.setUniqType(mConfig.uniqType);
				entity.setDownloadInfo(info);
				infoDAO.addDownloadTask(info);
				unfinishedList.add(info);
			}
		}
		info.setStatus(DownloadStatus.STATUS_WAIT);
		start(info);
	}

	private DownloadInfo queryDownloadInfo(T entity) {
		return queryDownloadInfo(mConfig.uniqType == UniqType.UniqId ? entity.getId() : entity
						.getDownloadUrl());
	}
	
	private DownloadInfo queryDownloadInfo(String uniq) {
		return infoDAO.queryDownloadTask(id, uniq, mConfig.uniqType);
	}

	private void start(DownloadInfo info) {
		DownloadTask tempTask = taskMap.get(info.getUniq());
		if (tempTask != null) {
			tempTask.cancel(true);
		}
		try {
			final DownloadTask task = new DownloadTask(info, mConfig.filePath);
			task.setListener(this);
			taskMap.put(info.getUniq(), task);
			Message msg = handler.obtainMessage(MSG_TASK);
			msg.obj = task;
			handler.sendMessage(msg);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void forAPI11(DownloadTask task) {
		task.executeOnExecutor(LIMITED_TASK_EXECUTOR, "");
	}

	@Override
	public void pause(T entity) {
		if (entity.getDownloadInfo() != null) {
			DownloadTask task = taskMap.get(entity.getDownloadInfo().getUniq());
			pause(task);
		}
	}
	
	private void pause(DownloadTask task){
		if (task != null) {
			task.cancel(true);
			task.getDownloadInfo().setStatus(DownloadStatus.STATUS_STOPPED);
			final Message msg = handler.obtainMessage(MSG_STATUS_CHANGE);
			msg.obj = task.getDownloadInfo();
			handler.sendMessage(msg);
		}
	}

	@Override
	public void pauseAll() {
		Set<String> set = taskMap.keySet();
		Iterator<String> iterator = set.iterator();
		while (iterator.hasNext()) {
			pause(taskMap.get(iterator.next()));
		}
	}
	
	@Override
	public void toggle(T entity) {
		if (entity == null) {
			return;
		}
		if (entity.getDownloadInfo() == null) { // 没有下载信息
			start(entity);
		}else{
			DownloadTask tempTask = taskMap.get(entity.getDownloadInfo().getUniq());
			if (tempTask != null && tempTask.getDownloadInfo() != null) {
				if (tempTask.getDownloadInfo().getStatus() == DownloadStatus.STATUS_RUNNING
						|| tempTask.getDownloadInfo().getStatus() == DownloadStatus.STATUS_WAIT) { // 状态为正在下载或等待中，则暂停下载
					pause(entity);
				} else {
					start(entity);
				}
			}
		}
	}

	@Override
	public void delete(T entity) {
		DownloadInfo info = entity.getDownloadInfo();
		if (info != null) {
			DownloadTask task = taskMap.get(entity.getDownloadInfo().getUniq());
			if (task != null) {
				task.cancel(true);
			}else{
				taskMap.remove(info.getUniq());
			}
		} else {
			info = queryDownloadInfo(entity);
			info.setUniqType(mConfig.uniqType);
		}
		if(info != null){
			unfinishedList.remove(entity);
			if (!TextUtils.isEmpty(info.getFilePath())) {
				File file = new File(info.getFilePath());
				if (file != null && file.exists()) {
					file.delete();
				}
			}
			infoDAO.deleteDownloadTask(info);
		}
	}

	@Override
	public DownloadInfo getDownloadInfo(String uniq) {
		return queryDownloadInfo(uniq);
	}

	@Override
	public List<DownloadInfo> getAllFinished() {
		return finishedList;
	}

	@Override
	public List<DownloadInfo> getAllUnFinished() {
		return unfinishedList;
	}
	
	@Override
	public List<DownloadInfo> getAll() {
		List<DownloadInfo> tasks = new ArrayList<DownloadInfo>();
		tasks.addAll(finishedList);
		tasks.addAll(unfinishedList);
		return tasks;
	}

	@Override
	public void destroy() {
		taskMap.clear();
		mListener = null;
		pauseAll();
		finishedList.clear();
		unfinishedList.clear();
	}

	@Override
	public void setConfig(DownloadEngineConfig config) {
		this.mConfig = config;
	}

	private long preTime;
	private long mUpdateInterval;

	@SuppressWarnings("unchecked")
	@Override
	public void onStatusChange(DownloadInfo info) {
		int status = info.getStatus();
		if (status == DownloadStatus.STATUS_CANCEL) {
			status = DownloadStatus.STATUS_STOPPED;
		}
		switch (status) {
		case DownloadStatus.STATUS_RUNNING:
			if (info.getStartTime() == 0) {
				info.setStartTime(System.currentTimeMillis());
			}
			if (mListener != null
					&& (System.currentTimeMillis() - preTime > mUpdateInterval || info
							.getTotalSize() == info.getDownloadSize())) {
				preTime = System.currentTimeMillis();
				mListener.onStatusChange(this, (T) info.getEntity());
			}
			break;
		case DownloadStatus.STATUS_WAIT:
		case DownloadStatus.STATUS_STOPPED:
			if (status == DownloadStatus.STATUS_STOPPED) {
				infoDAO.updateDownloadTask(info);
			}
			if (mListener != null) {
				mListener.onStatusChange(this, (T) info.getEntity());
			}
			break;
		case DownloadStatus.STATUS_FINISHED:
			info.setEndTime(System.currentTimeMillis());
			infoDAO.updateDownloadTask(info);
			taskMap.remove(info.getUniq());
			unfinishedList.remove(info);
			finishedList.add(info);
			if (mListener != null) {
				mListener.onStatusChange(this, (T) info.getEntity());
			}
			break;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onError(DownloadInfo info, Throwable erro) {
		infoDAO.updateDownloadTask(info);
		if (mListener != null) {
			mListener.onError(this, (T) info.getEntity(), erro);
		}
	}

	private DownloadEngineListener<T> mListener;

	@Override
	public void setDownloadEngineListener(DownloadEngineListener<T> listener) {
		mListener = listener;
	}

	@Override
	public DownloadEngineListener<T> getDownloadEngineListener() {
		return mListener;
	}

}
