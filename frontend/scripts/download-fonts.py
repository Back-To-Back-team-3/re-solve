from pathlib import Path
import subprocess
families={'blackhansans':['BlackHanSans-Regular.ttf'],'ibmplexsanskr':['IBMPlexSansKR-Regular.ttf','IBMPlexSansKR-Medium.ttf','IBMPlexSansKR-Bold.ttf'],'pressstart2p':['PressStart2P-Regular.ttf']}
root=Path(__file__).resolve().parent.parent/'public/fonts'
for family,files in families.items():
    for file in files+['OFL.txt']:
        dest=root/(family+'-OFL.txt' if file=='OFL.txt' else file)
        subprocess.run(['curl','--fail','--silent','--show-error','--location',f'https://raw.githubusercontent.com/google/fonts/main/ofl/{family}/{file}','--output',str(dest)],check=True)
        print(dest.name)
