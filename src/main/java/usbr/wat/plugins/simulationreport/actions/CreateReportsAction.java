/*
 * Copyright 2021  Hydrologic Engineering Center (HEC).
 * United States Army Corps of Engineers
 * All Rights Reserved.  HEC PROPRIETARY/CONFIDENTIAL.
 * Source may not be released without written approval
 * from HEC
 */
package usbr.wat.plugins.simulationreport.actions;

import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.python.core.PyCode;
import org.python.util.PythonInterpreter;

import com.rma.io.FileManagerImpl;
import com.rma.io.RmaFile;
import com.rma.model.Project;

import hec2.plugin.model.ModelAlternative;
import hec2.wat.model.WatSimulation;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporter;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionPanelPlugin;
import usbr.wat.plugins.actionpanel.actions.AbstractReportAction;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.io.ReportXmlFile;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;

/**
 * @author Mark Ackerman
 *
 */
@SuppressWarnings("serial")
public class CreateReportsAction extends AbstractReportAction
{
	private Logger _logger = Logger.getLogger(CreateReportsAction.class.getName());
	
	public static final String REPORT_INSTALL_FOLDER = "AutomatedReport";
	
	
	public static final String OBS_DATA_FOLDER   = "shared";
	public static final String REPORT_DIR = "reports";
	private static final String JASPER_REPORT_DIR = "Reports";
	public static final String JASPER_OUT_FILE = "WTMP_report_draft-";
	public static final String REPORT_FILE_EXT = ".pdf";
	
	private static final String SCRIPTS_DIR = "scripts";
	private static final String MAVEN_PATH = "usbr.wat.plugins/usbr-simulation-report";
	
	
	private PythonInterpreter  _interp;
	private PyCode _pycode;
	
	public CreateReportsAction()
	{
		super("Create Reports");
		setEnabled(false);
	}
	
	public boolean createReport(List<SimulationReportInfo> simInfos, ReportOptions options)
	{
		
		WatSimulation sim;
		long t1 = System.currentTimeMillis();
		try
		{
			for(int i = 0;i < simInfos.size(); i++ )
			{
				String xmlFile = createSimulationXmlFile(simInfos.get(i));
				if ( xmlFile != null )
				{
					if ( !editDataAdapterFile(simInfos.get(i).getSimFolder()))
					{
						return false;
					}
					if ( runPythonScript(xmlFile))
					{
						return runJasperReport(simInfos.get(0), options);
					}
				}
			}
		}
		finally
		{
			long t2 = System.currentTimeMillis();
			_logger.info("Took "+(t2-t1)+" to create "+getName());
		}
		return false;
	}
	
	/**
	 * @param sim
	 */
	private String createSimulationXmlFile(SimulationReportInfo info)
	{
		Project prj = Project.getCurrentProject();
		String studyDir = prj.getProjectDirectory();
		String simDir = info.getSimFolder();
		String filename = RMAIO.concatPath(simDir, REPORT_DIR);
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
				_logger.info("createReportAction:no ResSim Alternative found in Simulation "+sim);
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
			_logger.info("runProcess:time to run python for "+sim+" alt "+modelAlt+" is "+(t2-t1)+"ms");
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
			String jasperReportFolder = getJasperRelativeFolder();
			String jasperReportDir = RMAIO.concatPath(studyDir, REPORT_DIR);
			String rptFile = RMAIO.concatPath(jasperReportDir, JASPER_FILE);
			String installDir = System.getProperty("user.dir");
			installDir = RMAIO.getDirectoryFromPath(installDir);
			//rptFile = RMAIO.concatPath(rptFile, JASPER_FILE);


			
			String inJasperFile = rptFile;
			
			//configure the context
			SimpleJasperReportsContext context = new SimpleJasperReportsContext();

			JRPropertiesUtil.getInstance(context).setProperty("net.sf.jasperreports.xpath.executer.factory",
					"net.sf.jasperreports.engine.util.xml.JaxenXPathExecuterFactory");
			

			String outputFile = RMAIO.concatPath(simDir, REPORT_DIR);
			RmaFile simDirFile = FileManagerImpl.getFileManager().getFile(outputFile);
			if ( !simDirFile.exists() )
			{
				if ( !simDirFile.mkdirs())
				{
					_logger.info("runJasperReport:failed to create folder "+simDirFile.getAbsolutePath());
				}
			}

			if ( !compileJasperFiles(studyDir, installDir, jasperReportFolder))
			{
				return false;
			}	
			
			JasperPrint jasperPrint = fillReport(context, studyDir, installDir, jasperReportFolder, info, options);
			if ( jasperPrint == null )
			{
				return false;
			}

			outputFile = RMAIO.concatPath(outputFile, JASPER_OUT_FILE);
			SimpleDateFormat fmt= new SimpleDateFormat("yyyy.MM.dd-HHmm");

			Date date = new Date();
			outputFile = outputFile.concat(fmt.format(date));
			outputFile = outputFile.concat(REPORT_FILE_EXT);
			// fills compiled report with parameters and a connection
			JRExporter exporter = options.getOutputType().buildExporter(jasperPrint, outputFile);
			long t4 = System.currentTimeMillis();
			try
			{
				exporter.exportReport();
				_logger.info("runJasperReport:simulation report written to "+outputFile);
			}
			catch (JRException e)
			{
				e.printStackTrace();
				return false;
			}

			long t5 = System.currentTimeMillis();
			_logger.info("runJasperReport:time to write jasper simulation report for "+info.getName()+ "is "+(t5-t4)+"ms");
			return true;
		}
		finally
		{
			long end = System.currentTimeMillis();
			_logger.info("runJasperReport:total time to create jasper simulation report for "+info.getName()+" is "+(end-t1)+"ms");
		}
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

	@Override
	public String getMavenPath()
	{
		return MAVEN_PATH;
	}

}
