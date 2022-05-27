/*
 * Copyright 2021  Hydrologic Engineering Center (HEC).
 * United States Army Corps of Engineers
 * All Rights Reserved.  HEC PROPRIETARY/CONFIDENTIAL.
 * Source may not be released without written approval
 * from HEC
 */
package usbr.wat.plugins.simulationreport.actions;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.swing.AbstractAction;

import org.python.core.Py;
import org.python.core.PyCode;
import org.python.core.PyStringMap;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

import com.rma.io.FileManagerImpl;
import com.rma.io.RmaFile;
import com.rma.model.Project;

import hec2.plugin.model.ModelAlternative;
import hec2.wat.io.ProcessOutputReader;
import hec2.wat.model.WatSimulation;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporter;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.data.JRXmlDataSource;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRXmlUtils;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;
import rma.util.RMAFilenameFilter;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionPanelPlugin;
import usbr.wat.plugins.actionpanel.io.OutputType;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.io.ReportXmlFile;
import usbr.wat.plugins.actionpanel.model.ReportPlugin;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;

/**
 * @author Mark Ackerman
 *
 */
@SuppressWarnings("serial")
public class CreateReportsAction extends AbstractAction
	implements ReportPlugin
{
	public static final String PYTHON_REPORT_BAT = "WAT_Report_Generator.exe";
	public static final String PYTHON_INIT_BAT = "initializePython.bat";
	public static final String JYTHON_POST_PROCESS_SCRIPT ="PostProcess_Region.py";
	public static final String REPORT_INSTALL_FOLDER = "AutomatedReport";
	
	public static final String JASPER_COMPILED_FILE_EXT = ".jasper";
	private static final String JASPER_SOURCE_FILE_EXT = "jrxml";
	
	public static final String OBS_DATA_FOLDER   = "shared";
	private static final String DATA_SOURCES_DIR = "DataSources";
	public static final String REPORT_DIR = "reports";
	private static final String JASPER_REPORT_DIR = "Reports";
	private static final String JASPER_FILE = "USBR_Draft_Validation.jrxml";
	public static final String JASPER_OUT_FILE = "WTMP_report_draft-";
	public static final String REPORT_FILE_EXT = ".pdf";
	private static final String XML_DATA_DOCUMENT = "USBRAutomatedReportDataAdapter.xml";
	
	private static final String WATERSHED_NAME_PARAM = "watershedName";
	private static final String SIMULATION_NAME_PARAM = "simulationName";
	private static final String ANALYSIS_START_TIME_PARAM = "analysisStartTime";
	private static final String ANALYSIS_END_TIME_PARAM = "analysisEndTime";
	private static final String SIMULATION_LAST_COMPUTED_DATE_PARAM = "simulationDate";
	private static final String PRINT_HEADER_FOOTER_PARAM = "printHeaderAndFooter";
	private static final String SCRIPTS_DIR = "scripts";
	
	
	private PythonInterpreter  _interp;
	private PyCode _pycode;
	
	public CreateReportsAction()
	{
		super("Create Reports");
		setEnabled(false);
	}
	@Override
	public void actionPerformed(ActionEvent e)
	{
		ReportOptions options = new ReportOptions();
		options.setOutputType(OutputType.PDF);
		createReport(options);
	}
	public boolean createReport(ReportOptions options)
	{
		
		WatSimulation sim;
		long t1 = System.currentTimeMillis();
		List<SimulationReportInfo>simInfos = ActionPanelPlugin.getInstance().getActionsWindow().getSimulationReportInfos();
		String xmlFile = createSimulationXmlFile(simInfos.get(0));
		if ( xmlFile != null )
		{
			if ( runPythonScript(xmlFile))
			{
				return runJasperReport(simInfos.get(0), options);
			}
		}
		return false;
	}
	/**
	 * @param reportFile
	 * @param pythonReportBat
	 * @return
	 */
	private static boolean runPythonScript(String reportXmlFile)
	{
		long t1 = System.currentTimeMillis();
		try
		{
			if ( Boolean.getBoolean("SkipPythonReport"))
			{
				return true;
			}
			List<String>cmdList = new ArrayList<>();
			String dir = getDirectoryToUse();
			
			String exeFile = RMAIO.concatPath(dir, PYTHON_REPORT_BAT);
			//cmdList.add("cmd.exe");
			//cmdList.add("/c");
			cmdList.add(exeFile);
			cmdList.add(reportXmlFile);

			return runProcess(cmdList, dir);
		}
		finally
		{
			long t2 = System.currentTimeMillis();
			System.out.println("runPythonScript:time to run python for "+reportXmlFile+" is "+(t2-t1)+"ms");
		}
	}
	/**
	 * @param sim
	 */
	private static String createSimulationXmlFile(SimulationReportInfo info)
	{
		Project prj = Project.getCurrentProject();
		String studyDir = prj.getProjectDirectory();
		String filename = RMAIO.concatPath(studyDir, REPORT_DIR);
		filename = RMAIO.concatPath(filename, RMAIO.userNameToFileName(info.getName())+".xml");
		if ( Boolean.getBoolean("SkipSimulationReportFile"))
		{
			return filename;
		}
		
		ReportXmlFile xmlFile = new ReportXmlFile(filename);
		xmlFile.setStudyInfo(studyDir, getObsDataPath(studyDir));
		List<SimulationReportInfo>sims = new ArrayList<>();
		sims.add(info);
		xmlFile.setSimulationInfo(ActionPanelPlugin.getInstance().getActionsWindow().getSimulationGroup().getName(), sims);
		if (  xmlFile.createXMLFile() )
		{
			return filename;
		}
		return null;
	}
	/*
	private void runSimulationReport(WatSimulation sim)
	{
		List<ModelAlternative> modelAlts = sim.getAllModelAlternativeList();
		ModelAlternative modelAlt;
		
		String simName = sim.getName();
		String groupName = ActionPanelPlugin.getInstance().getActionsWindow().getSimulationGroup().getName();
		String baseSimulationName = RMAIO.replace(simName, "-"+groupName, "");
		
		if ( !Boolean.getBoolean("SkipPythonReport"))
		{
			if ( !runPythonInitScript(sim))
			{
				ActionPanelPlugin.getInstance().getActionsWindow().addMessage("Failed to initialize report script for " + sim);
				return;
			}
			for(int m = 0;m < modelAlts.size(); m++ )
			{
				modelAlt= modelAlts.get(m);
				if ( modelAlt == null )
				{
					continue;
				}
				if ( runJythonScript(sim, modelAlt, baseSimulationName))
				{
					if ( !runPythonScript(sim, modelAlt, baseSimulationName))
					{
						ActionPanelPlugin.getInstance().getActionsWindow().addMessage("Failed to generate report input for " + sim+" Alternative "+modelAlt);
						return;
					}
				}
				else
				{
					ActionPanelPlugin.getInstance().getActionsWindow().addMessage("Failed to generate report input for " + sim+" Alternative "+modelAlt);
					return;
				}
							
			}
		}
		if ( !runJasperReport(sim))
		{
			ActionPanelPlugin.getInstance().getActionsWindow().addMessage("Failed to generate report file for " + sim);
		}
		else
		{
			ActionPanelPlugin.getInstance().getActionsWindow().addMessage("Generated Report File for "+ sim);
		}
		
	}
	*/
	/**
	 * @return
	 */
	private boolean runJythonScript(WatSimulation sim, ModelAlternative modelAlt, String baseSimulationName)
	{
		/*
		studyDir,
		simDir,
		program name ( ResSim, RAS etc)
		fpart (ressim .h5 file)
		obs data folder
		model alternative name
		simulation name (for the .rptgen file)

		 */
		long t1 = System.currentTimeMillis();
		String studyDir = Project.getCurrentProject().getProjectDirectory();
		
		
		PyStringMap locals = new PyStringMap();
		locals.__setitem__("startTime", Py.java2py(sim.getRunTimeWindow().getStartTimeString()));
		locals.__setitem__("endTime", Py.java2py(sim.getRunTimeWindow().getEndTimeString()));
		locals.__setitem__("studyFolder", Py.java2py(studyDir));
		locals.__setitem__("simulationFolder", Py.java2py(sim.getSimulationDirectory()));
		locals.__setitem__("modelName", Py.java2py(modelAlt.getProgram()));
		locals.__setitem__("alternativeName", Py.java2py(modelAlt.getName()));
		locals.__setitem__("alternativeFpart", Py.java2py(sim.getFPart(modelAlt)));
		locals.__setitem__("baseSimulationName", Py.java2py(baseSimulationName));
		locals.__setitem__("obsDataFolder", Py.java2py(getObsDataPath(studyDir)));
		locals.__setitem__("dssFile", Py.java2py(sim.getSimulationDssFile()));
		locals.__setitem__("simulationName", Py.java2py(sim.getName()));
	
		
		// set additional variables....
		PythonInterpreter interp = getInterpreter();
		interp.setLocals(locals);
		String script = getJythonScript();
		if ( script == null || script.isEmpty())
		{
			System.out.println("runJythonScript:null or empty jython script");
			return false;
		}
		PyCode pyCode = getPyCode(script);
		
		try	
		{
			interp.exec(_pycode);
			
			long t2 = System.currentTimeMillis();
			System.out.println("runJythonScript:time to run jython script "+(t2-t1)+" ms");
			return true;
		}
		catch (Exception e )
		{
			System.out.println("runJythonScript:error running jython script "+e);
		}
		
		return false;
	}
	/**
	 * @param script
	 * @return
	 */
	private PyCode getPyCode(String script)
	{
		if ( _pycode == null || Boolean.getBoolean("Jython.CompileEveryTime"))
		{
			_pycode = new org.python.util.PythonInterpreter().compile(script);
		}
		return _pycode;
	}
	/**
	 * @return
	 */
	private String getJythonScript()
	{
		String dir = Project.getCurrentProject().getProjectDirectory();
		String scriptsDir = RMAIO.concatPath(dir, SCRIPTS_DIR);
		String jythonScript = RMAIO.concatPath(scriptsDir, JYTHON_POST_PROCESS_SCRIPT);
		RmaFile jythonFile = FileManagerImpl.getFileManager().getFile(jythonScript);
		
		StringBuilder builder = new StringBuilder();
		String line;
		
		BufferedReader reader = jythonFile.getBufferedReader();
		if ( reader == null )
		{
			System.out.println("getJythonScript:failed to find file "+jythonFile.getAbsolutePath());
			return null;
		}
		try
		{
			while ( (line = reader.readLine())!= null )
			{
				builder.append(line);
				builder.append("\n");
			}
		}
		catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		finally
		{
			try
			{
				reader.close();
			}
			catch (IOException e)
			{ }
		}
		return builder.toString();
		
	}
	private PythonInterpreter getInterpreter()
	{
		if ( _interp == null )
		{
			//------------------------------------------------------//
			// make sure we have a valid application home directory //
			//------------------------------------------------------//
			String appHome = hec.lang.ApplicationProperties.getAppHome();
			if (appHome == null) appHome = ".";
			try {
				appHome = (new File(appHome)).getAbsolutePath();
				if (appHome.endsWith(File.separator+".")) {
					appHome = appHome.substring(0, appHome.length() - 2);
				}
			}
			catch (Exception e) {
			}
			long t1 = System.currentTimeMillis();
			String pythonPath = System.getProperty("python.path");
			if (pythonPath == null)
			{
				pythonPath = appHome;
				String classpath = System.getProperty("java.class.path");
				StringTokenizer tokenizer = new StringTokenizer(classpath, File.pathSeparator);
				String token = null;
				boolean found = false;
				while ( tokenizer.hasMoreTokens())
				{
					token = tokenizer.nextToken();
					if ( token.indexOf("jythonlib.jar") > -1 )
					{
						found = true;
						System.out.println("found jythonlib.jar in classpath"+token);
						break;
					}
				}
				if ( found )
				{
					token = token+"/lib";
				}
				else
				{
					token = appHome+File.separator+"jar"+File.separator+"jythonlib.jar/lib";
				}
				if (!pythonPath.endsWith(File.separator)) pythonPath += File.separator;
				pythonPath += "scripts"+File.pathSeparator+token;

				System.setProperty("python.path", pythonPath);
			}
			java.util.Properties props = new java.util.Properties();
			props.setProperty("python.path", pythonPath);

			PythonInterpreter.initialize(System.getProperties(), props,
				new String[] {""});
			PySystemState sys = Py.getSystemState();
			sys.add_package("hec.rss.model");
			//sys.add_classdir("/dev/code");

			_interp = new PythonInterpreter();
			

		}
		return _interp;
	}
	/**
	 * @param sim
	 * @return
	 */
	private static boolean runPythonInitScript(WatSimulation sim)
	{
		long t1 = System.currentTimeMillis();
		try
		{
			
			String studyDir = Project.getCurrentProject().getProjectDirectory();

			List<String>cmdList = new ArrayList<>();
			String batFile = RMAIO.concatPath(studyDir, PYTHON_INIT_BAT);
			cmdList.add(batFile);
			cmdList.add(studyDir);

			return runProcess(cmdList, studyDir);
		}
		finally
		{
			long t2 = System.currentTimeMillis();
			System.out.println("runPythonInitScript: time to run python init script is "+(t2-t1)+"ms");
		}
	
	}
	/**
	 * run the bat file to create the XMl file that's input to the jasper report
	 * @param sim
	 * @return
	 */
	public boolean runPythonScript(WatSimulation sim, ModelAlternative modelAlt, String baseSimulationName)
	{
		
		
		// first run the python script through the .bat file
		// bat file needs: 
		// 1. watershed path
		// 2. simulation path
		// 3. model name ... i.e. ResSim
		// 4. alternative's F-Part
		// 5. folder to the observation data in the study
		// 6. alternative's name
		// 7. simulation's base name
		
		long t1 = System.currentTimeMillis();
		try
		{
			String fpart = findFpartForPython(sim, modelAlt);
			if ( fpart == null )
			{
				System.out.println("createReportAction:no ResSim Alternative found in Simulation "+sim);
				return false;
			}

			List<String>cmdList = new ArrayList<>();
			String dirToUse = getDirectoryToUse();
			String exeFile = RMAIO.concatPath(dirToUse, PYTHON_REPORT_BAT);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			//cmdList.add("cmd.exe");
			//cmdList.add("/c");
			cmdList.add(exeFile);
			cmdList.add(studyDir);
			// hack for having a comma in the path and the RMAIO.userNameToFileName() not catching it
			String simDir = sim.getSimulationDirectory();
			simDir = RMAIO.removeChar(simDir, ',');
			cmdList.add(simDir);
			cmdList.add(modelAlt.getProgram());
			cmdList.add(fpart);
			String obsPath = getObsDataPath(studyDir);
			cmdList.add(obsPath);
			cmdList.add(modelAlt.getName());
			cmdList.add(baseSimulationName);



			return runProcess(cmdList, dirToUse);
		}
		finally
		{
			long t2 = System.currentTimeMillis();
			System.out.println("runProcess:time to run python for "+sim+" alt "+modelAlt+" is "+(t2-t1)+"ms");
		}
		
	}
	
	/**
	 * @return
	 */
	private static String getDirectoryToUse()
	{
		String dir = System.getProperty("WAT.InstallDir", null);
		if ( dir == null || dir.isEmpty())
		{
			dir = System.getProperty("user.dir");
			System.out.println("getDirectoryToUse:WAT.InstallDir not set using "+dir);
		}
		else
		{
			System.out.println("getDirectoryToUse:WAT.InstallDir set to "+dir);
		}
					
		dir = RMAIO.concatPath(dir, REPORT_INSTALL_FOLDER);
		return dir;
	}
	/**
	 * @param studyDir
	 * @return
	 */
	private static String getObsDataPath(String studyDir)
	{
		return RMAIO.concatPath(studyDir, OBS_DATA_FOLDER);
	}
	
	private static boolean runProcess(List<String> cmdList, String runInFolder)
	{
		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);
		ProcessBuilder procBuilder = new ProcessBuilder(cmdArray);
		
		File f = new File(runInFolder);
		if (!f.exists())
		{
			f.mkdirs();
		}
		procBuilder.directory(f);
		try
		{
			System.out.println("runProcess:launching in folder:"+runInFolder);
			System.out.println("runProcess:launching: "+cmdList);
			Process proc = procBuilder.start();
			BufferedReader reader1 = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
			ProcessOutputReader preader1 = new ProcessOutputReader(reader1, true, proc);
			preader1.setEchoOutput(true);
			preader1.start();
			BufferedReader reader2 = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			ProcessOutputReader preader2 = new ProcessOutputReader(reader2, false, proc);
			preader2.setEchoOutput(true);
			preader2.start();
			int rv = proc.waitFor();
			System.out.println("runProcess:rv="+rv);
			return rv == 0;

		}
		catch (IOException | InterruptedException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		
			
	}
	/**
	 * @param outputType 
	 * @param sim
	 */
	public boolean runJasperReport(SimulationReportInfo info, ReportOptions options)
	{
		long t1 = System.currentTimeMillis();
		try
		{
			//Log log = LogFactory.getLog(JasperFillManager.class);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			String simDir = info.getSimFolder();
			String jasperRepoDir = RMAIO.concatPath(studyDir, REPORT_DIR);
			String rptFile = RMAIO.concatPath(jasperRepoDir, JASPER_FILE);
			//rptFile = RMAIO.concatPath(rptFile, JASPER_FILE);


			System.out.println("runReportWithOutputFile:report repository:"+jasperRepoDir);

			SimpleJasperReportsContext context = new SimpleJasperReportsContext();
			FileRepositoryService fileRepository = new FileRepositoryService(context, 
					jasperRepoDir, true);
			context.setExtensions(RepositoryService.class, Collections.singletonList(fileRepository));
			context.setExtensions(PersistenceServiceFactory.class, 
					Collections.singletonList(FileRepositoryPersistenceServiceFactory.getInstance()));
			String inJasperFile = rptFile;

			JRPropertiesUtil.getInstance(context).setProperty("net.sf.jasperreports.xpath.executer.factory",
					"net.sf.jasperreports.engine.util.xml.JaxenXPathExecuterFactory");



			long t2 = System.currentTimeMillis();
			JasperReport jasperReport;
			try
			{
				compileJasperFiles(RMAIO.getDirectoryFromPath(inJasperFile));
				jasperReport = JasperCompileManager.compileReport(inJasperFile);
			}
			catch (JRException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			long t3 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to compile jasper files for "+info.getName()+ "is "+(t3-t2)+"ms");

			String outputFile = RMAIO.concatPath(simDir, REPORT_DIR);
			RmaFile simDirFile = FileManagerImpl.getFileManager().getFile(outputFile);
			if ( !simDirFile.exists() )
			{
				if ( !simDirFile.mkdirs())
				{
					System.out.println("runJasperReport:failed to create folder "+simDirFile.getAbsolutePath());
				}
			}
			outputFile = RMAIO.concatPath(outputFile, JASPER_OUT_FILE);

			Map<String, Object>params = new HashMap<>();
			// define the parameters for the report
			params.put("p_ReportFolder", jasperRepoDir);
			params.put(WATERSHED_NAME_PARAM, Project.getCurrentProject().getName());
			params.put(SIMULATION_NAME_PARAM, info.getName());
			params.put(ANALYSIS_START_TIME_PARAM, info.getSimulation().getRunTimeWindow().getStartTime().toString());
			params.put(ANALYSIS_END_TIME_PARAM, info.getSimulation().getRunTimeWindow().getEndTime().toString());
			params.put(PRINT_HEADER_FOOTER_PARAM, options.shouldPrintHeadersFooters());	
			Date date = new Date(info.getLastComputedDate());
			SimpleDateFormat fmt = new SimpleDateFormat("MMMM dd, yyyy HH:mm");

			params.put(SIMULATION_LAST_COMPUTED_DATE_PARAM, fmt.format(date));
			
			
			
			fmt= new SimpleDateFormat("yyyy.MM.dd-HHmm");
			
			date = new Date();
			outputFile = outputFile.concat(fmt.format(date));
			outputFile = outputFile.concat(REPORT_FILE_EXT);
			

			String xmlDataDoc = RMAIO.concatPath(studyDir, REPORT_DIR);
			xmlDataDoc = RMAIO.concatPath(xmlDataDoc, DATA_SOURCES_DIR);
			xmlDataDoc = RMAIO.concatPath(xmlDataDoc, XML_DATA_DOCUMENT);

			JasperPrint jasperPrint;
			System.out.println("runJasperReport:filling report "+inJasperFile);
			JRXmlDataSource dataSource;
			try
			{
				dataSource = new JRXmlDataSource(context, JRXmlUtils.parse(JRLoader.getLocationInputStream(xmlDataDoc)));
			}
			catch (JRException e1)
			{
				e1.printStackTrace();
				return false;
			}
			try
			{
				jasperPrint = JasperFillManager.getInstance(context).fill(jasperReport, params, dataSource);
			}
			catch (JRException e)
			{
				e.printStackTrace();
				return false;
			}
			long t4 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to fill jasper report for "+info.getName()+ "is "+(t4-t3)+"ms");

			// fills compiled report with parameters and a connection
			JRExporter exporter = options.getOutputType().buildExporter(jasperPrint, outputFile);
			//JRPdfExporter exporter = new JRPdfExporter();
			//exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
			//exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outputFile));

			try
			{
				exporter.exportReport();
				System.out.println("runJasperReport:report written to "+outputFile);
			}
			catch (JRException e)
			{
				e.printStackTrace();
				return false;
			}

			long t5 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to write jasper report for "+info.getName()+ "is "+(t5-t4)+"ms");
			return true;
		}
		finally
		{
			long end = System.currentTimeMillis();
			System.out.println("runJasperReport:total time to create jasper report for "+info.getName()+" is "+(end-t1)+"ms");
		}
	}
	/**
	 * @param directoryFromPath
	 */
	private static void compileJasperFiles(String jasperDir)
	{
		RMAFilenameFilter filter= new RMAFilenameFilter(JASPER_SOURCE_FILE_EXT);
		filter.setAcceptDirectories(false);
		List<String> jasperFiles = FileManagerImpl.getFileManager().list(jasperDir, filter);
		String srcFile, destFile;
		boolean alwaysCompile = Boolean.getBoolean("CompileJasperFiles");
		for (int i = 0;i < jasperFiles.size(); i++ )
		{
			srcFile = jasperFiles.get(i);
			destFile = getJasperDestFile(srcFile);
			if ( needsToCompile(srcFile, destFile) || alwaysCompile )
			{
				System.out.println("compileJasperFiles:compiling to disk "+srcFile);
				try
				{
					String rv = JasperCompileManager.compileReportToFile(jasperFiles.get(i));
					System.out.println("compileJasperFiles: compiled to "+rv);
				}
				catch (JRException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
	}
	/**
	 * 
	 * check to see if the file needs to be compile
	 * @param srcFile
	 * @param destFile
	 * @return
	 */
	private static boolean needsToCompile(String src, String dest)
	{
		RmaFile srcFile = FileManagerImpl.getFileManager().getFile(src);
		RmaFile destFile = FileManagerImpl.getFileManager().getFile(dest);
		if ( destFile.exists() )
		{
			if ( srcFile.lastModified() > destFile.lastModified() )
			{
				return true;
			}
			return false;
		}
		return true;
	}
	/**
	 * @param srcFile
	 * @return
	 */
	private static String getJasperDestFile(String srcFile)
	{
		int idx = srcFile.lastIndexOf('.');
		if ( idx > -1 )
		{
			String destFile = srcFile.substring(0,idx);
			destFile = destFile.concat(JASPER_COMPILED_FILE_EXT);
			return destFile;
		}
		return null;
	}
	/**
	 * @param sim
	 * @return
	 */
	private static String findFpartForPython(WatSimulation sim, ModelAlternative modelAlt)
	{
		String fpart = sim.getFPart(modelAlt);
		return RMAIO.userNameToFileName(fpart);
	}
	
	@Override
	public String getName()
	{
		return "Simulation Report";
	}
	@Override
	public String getDescription()
	{
		return "Report for a single Simulation";
	}
	@Override
	public boolean isComparisonReport()
	{
		return false;
	}
	@Override
	public boolean isIterationReport()
	{
		return false;
	}
	
	@Override
	public String toString()
	{
		return getName();
	}
	public static void register()
	{
		ReportsManager.register(new CreateReportsAction());
	}
	
	public static void main(String[] args)
	{
		EventQueue.invokeLater(()->register());
	}

}
