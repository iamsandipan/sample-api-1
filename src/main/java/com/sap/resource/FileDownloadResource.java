package com.sap.resource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;

import com.google.gson.Gson;

@Controller
@RequestMapping(value = "/api")

public class FileDownloadResource {

	private static final String FILE_DOWNLOAD_ENDPOINT = "http://localhost:8080/api/download/file/";

	@Autowired
	private UserFileRepository userFileRepository;

	@Autowired
	private FileDownloadTrackerRepository fileDownloadTrackerRepository;

	@RequestMapping(value = "/download/files", method = RequestMethod.GET)
	public void downloadAll(HttpServletResponse resp) throws InterruptedException, ExecutionException {
		try {
			populateTrackerTable();
			startProcess();

		} catch (Exception e) {
			// TODO Auto-generated catch block
			//Log message
		}
		resp.setStatus(200);
	}

	@Async
	public void startProcess() throws InterruptedException, ExecutionException {

		List<FileDownloadTracker> files = fileDownloadTrackerRepository.findFilesWithStatus(DownloadStatus.NOT_STARTED.name(), new PageRequest(0, 10));

		while (files.size() > 0) {
			for (FileDownloadTracker file : files) {
				asyncUpdate(file);
			}
			files = fileDownloadTrackerRepository.findFilesWithStatus(DownloadStatus.NOT_STARTED.name(), new PageRequest(0, 10));
		}
	}

	@Async
	private void populateTrackerTable() throws Exception {
		int retry = 0;

		try {
			List<FileDownloadTracker> trackers = new ArrayList<FileDownloadTracker>(100);
			long maxFileNum = 0;
			//Call API to list files
			List<UserFile> userfiles = downloadFileInfo(0, 10);
			while (!userfiles.isEmpty()) {
				for (int i = 0; i < userfiles.size(); i++) {
					UserFile file = (UserFile)(userfiles.get(i));
					FileDownloadTracker tracker = buildTracker(file);
					trackers.add(tracker);
					maxFileNum = file.getFileNum();
				}

				if (trackers.size() > 0) {
					//Not sure if this is a transaction it needs to be 
					saveTrackers(retry, trackers);
				}
				userfiles = downloadFileInfo(maxFileNum, 10);
			}
		} catch (Exception e1) {
			e1.printStackTrace();
			throw e1;
		}
	}

	private int saveTrackers(int retry, List<FileDownloadTracker> trackers) {
		boolean success = false;
		while(!success){
			try{
				if(retry > 3){
					throw new RuntimeException("Unable to insert in local db, Exiting");
				}
				fileDownloadTrackerRepository.save(trackers);
				success = true;
			}catch(Exception e){
				retry ++;
			}
		}
		return retry;
	}

	private FileDownloadTracker buildTracker(UserFile file) {
		FileDownloadTracker tracker = new FileDownloadTracker();
		tracker.setId(UUID.randomUUID().toString());
		tracker.setFileNum(file.getFileNum());
		tracker.setStatus(DownloadStatus.NOT_STARTED.name());
		tracker.setName(file.getName());
		return tracker;
	}

	@Async
	public void asyncUpdate(FileDownloadTracker fileTracker) {
		try {
			byte[] bytes = downloadFile(fileTracker);
			saveFileToLocalDir(bytes, fileTracker.getName());
			setStatusCompleted(fileTracker);
		} catch (Exception e) {
			//No need to do retry as it will be tried in next batch

			e.printStackTrace();
		}
	}

	private void setStatusCompleted(FileDownloadTracker fileTracker) {
		fileTracker.setStatus(DownloadStatus.COMPLETED.name());
		fileDownloadTrackerRepository.save(fileTracker);
	}

	private List<UserFile> downloadFileInfo(long startFileNum, int count) throws InterruptedException {
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
		HttpEntity<String> entity = new HttpEntity<String>(headers);

		String url = "http://localhost:8080/api/download/file/" + startFileNum + "/count/"+ count;
		ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
		int retryCount = 0;
		while((response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) && (retryCount < 3)){
			response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			Thread.sleep(10000);
			retryCount ++;
		}
		if(retryCount >= 3){
			throw new RuntimeException("Unresponsive api");
		}
		String str = response.getBody();
		UserFile[] files =  new Gson().fromJson(str,  UserFile[].class);
		return Arrays.asList(files) ;
	}
	
	private byte[] downloadFile(FileDownloadTracker fileTracker) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters().add(new ByteArrayHttpMessageConverter());
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM));
		HttpEntity<String> entity = new HttpEntity<String>(headers);

		String url = FILE_DOWNLOAD_ENDPOINT + fileTracker.getName();
		ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
		return response.getBody();
	}

	private void saveFileToLocalDir(byte[] bytes, String name)
			throws FileNotFoundException, IOException {
		String currentDir = Paths.get(".").toAbsolutePath().normalize().toString();
		File f = new File(currentDir + "/download/" + name);
		OutputStream outputStream = new FileOutputStream(f);
		outputStream.write(bytes);
		outputStream.close();
		
	}

}
